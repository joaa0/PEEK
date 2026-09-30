package io.peek.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.peek.core.events.*;
import io.peek.core.exceptions.*;
import io.peek.core.integrations.MockInventoryCorrectionOutboundAdapter;
import io.peek.core.mcp.*;
import io.peek.core.orchestration.*;
import io.peek.core.products.*;
import io.peek.core.reconciliation.EvaluationService;
import io.peek.core.shared.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"peek.mcp.enabled=true", "peek.mcp.token=test-only-token-with-at-least-32-characters"})
@Import(McpAgentIT.FixedTime.class)
class InventoryCorrectionIT {
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("PEEK_TEST_DB_URL", "jdbc:postgresql://localhost:5432/peek_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PEEK_TEST_DB_USER", "peek"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("PEEK_TEST_DB_PASSWORD", "peek_local_only"));
    }
    @Autowired ProductService products;
    @Autowired EventService events;
    @Autowired ExceptionService exceptions;
    @Autowired CommandService commands;
    @Autowired EvaluationService evaluator;
    @Autowired PeekAgentTools tools;
    @Autowired AgentActionService actionViews;
    @Autowired AgentActionRepository actions;
    @Autowired InventoryCorrectionService corrections;
    @Autowired McpAgentIT.MutableClock clock;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.peek.core.demo.DemoResetService reset;
    @MockitoSpyBean MockInventoryCorrectionOutboundAdapter adapter;
    private BigDecimal savedTolerance;

    @BeforeEach void setup() {
        clock.set(NOW);
        savedTolerance = jdbc.queryForObject("select physical_stock_tolerance from demo_configuration where id=1", BigDecimal.class);
        jdbc.update("update demo_configuration set physical_stock_tolerance=0 where id=1");
    }
    @AfterEach void restore() { jdbc.update("update demo_configuration set physical_stock_tolerance=? where id=1", savedTolerance); }
    private record Fixture(UUID product, String sku, ProductService.MappingView mapping, UUID count, UUID exception) {}

    private NormalizedEvent event(Fixture f, String type, String quantity, String stock, boolean confirmed, Instant at) {
        return events.ingest(new EventInput(f.mapping().channel(), UUID.randomUUID().toString(), type, at,
            f.product(), f.sku(), f.mapping().externalId(), "ORDER-" + f.product(), null, "RECEIPT", null,
            quantity == null ? null : new BigDecimal(quantity), stock == null ? null : new BigDecimal(stock),
            confirmed, Map.of())).event();
    }
    private Fixture scenario(String physical, boolean confirmed) { return scenario(physical, confirmed, null); }
    private Fixture scenario(String physical, boolean confirmed, String runId) {
        clock.set(NOW);
        String suffix = UUID.randomUUID().toString();
        String sku = runId == null ? "COR-" + suffix : "CAM-" + runId;
        UUID product = products.create(new ProductService.ProductInput(sku, "Guarded stock correction", null, null, null,
            runId == null ? null : "Demo")).value().id();
        var mapping = products.addMapping(product, new ProductService.MappingInput("inventory-" + suffix, "ITEM-" + suffix, MappingStatus.ACTIVE)).value();
        var f = new Fixture(product, sku, mapping, null, null);
        event(f, "STOCK_UPDATED", null, "100", false, NOW.minusSeconds(30));
        var sale = event(f, "SALE_CONFIRMED", "5", null, false, NOW.minusSeconds(20));
        var sync = commands.create(CommandKind.INVENTORY_SYNC,
            new CommandService.CommandInput(sale.id(), mapping.channel(), "sync-" + suffix, true)).command();
        clock.set(sync.deadlineAt().plusSeconds(1));
        evaluator.evaluate(clock.instant());
        var alert = exceptions.forProduct(product).stream().filter(e -> e.code() == ExceptionCode.E01).findFirst().orElseThrow();
        var count = physical == null ? null : event(f, "PHYSICAL_COUNT", physical, null, confirmed, clock.instant().minusSeconds(1));
        return new Fixture(product, sku, mapping, count == null ? null : count.id(), alert.id());
    }
    private InventoryCorrectionService.Candidate candidate(Fixture f) {
        return tools.operationalContext(f.exception()).correctionCandidates().stream()
            .filter(c -> c.mappingId().equals(f.mapping().id())).findFirst().orElseThrow();
    }
    private String key(Fixture f, String key) { return key.trim() + "-" + f.product(); }
    private InventoryCorrectionService.CorrectionResult correct(Fixture f, String key) {
        return corrections.correct(f.exception(), f.mapping().id(), key(f, key), candidate(f).decisionFingerprint());
    }
    private NormalizedEvent confirm(Fixture f, CommandService.CommandView command, String order, String amount, Instant at) {
        return events.ingest(new EventInput(f.mapping().channel(), "CONFIRM-" + UUID.randomUUID(), "STOCK_UPDATED", at,
            f.product(), f.sku(), f.mapping().externalId(), order, null, null, null,
            null, new BigDecimal(amount), false, Map.of())).event();
    }
    private JsonNode rpc(Map<String, Object> args) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("test-only-token-with-at-least-32-characters"); headers.set("Accept", "application/json, text/event-stream");
        var body = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params",
            Map.of("name", InventoryCorrectionService.TOOL, "arguments", args));
        return http.postForEntity("/mcp", new HttpEntity<>(body, headers), JsonNode.class).getBody();
    }
    private Map<String, Object> arguments(Fixture f, String key, String fingerprint) {
        return Map.of("exceptionId", f.exception(), "mappingId", f.mapping().id(), "idempotencyKey", key(f, key), "decisionFingerprint", fingerprint);
    }

    @Test void exposesOnlyBackendDerived95AndRecordsExactAdapterTargetAndImmutableAttempt() {
        var f = scenario("95", true); var c = candidate(f);
        assertTrue(c.eligible(), c.reason()); assertEquals(95, c.expectedStock().intValue());
        assertEquals(95, c.physicalStock().intValue()); assertEquals(100, c.systemStock().intValue()); assertEquals(95, c.targetStock().intValue());
        var result = rpc(arguments(f, "eligible", c.decisionFingerprint()));
        assertFalse(result.path("result").path("isError").asBoolean(), result.toString());
        var id = UUID.fromString(result.path("result").path("structuredContent").path("command").path("id").asText());
        var command = commands.get(id);
        assertEquals(CommandKind.INVENTORY_CORRECTION, command.kind()); assertEquals(f.count(), command.triggerEventId());
        assertEquals(95, command.expectedStock().intValue()); assertEquals(95, command.requestedQuantity().intValue());
        assertEquals(1, command.attempts().size()); assertEquals("ACCEPTED", command.attempts().getFirst().result());
        var request = ArgumentCaptor.forClass(OutboundCommandAdapter.DispatchRequest.class);
        verify(adapter).dispatch(request.capture()); assertEquals(95, request.getValue().expectedStock().intValue());
        assertEquals(f.mapping().id(), request.getValue().mappingId());
        assertEquals(95, jdbc.queryForObject("select target_stock from mock_inventory_correction where command_id=?", BigDecimal.class, id).intValue());
        var action = actions.findByCommandIdOrderByStartedAtDesc(id).getFirst();
        assertEquals(f.count(), action.physicalCountEventId); assertEquals(c.decisionFingerprint(), action.decisionFingerprint);
        assertTrue(action.inputSummary.contains("systemStock=100")); assertEquals("PENDING_VERIFICATION", action.verificationStatus);
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exception()).status()); assertEquals(3, events.forSku(f.sku()).size());
    }

    @Test void arbitraryQuantityAndMissingFingerprintNeverReachAdapter() {
        var f = scenario("95", true); var args = new HashMap<>(arguments(f, "override", candidate(f).decisionFingerprint()));
        for (String field : List.of("targetStock", "quantity", "stock")) {
            args.put(field, 999); assertEquals(-32602, rpc(args).path("error").path("code").asInt()); args.remove(field);
        }
        args.remove("decisionFingerprint"); assertEquals(-32602, rpc(args).path("error").path("code").asInt());
        verify(adapter, never()).dispatch(any()); assertTrue(actions.findByExceptionIdOrderByStartedAtDesc(f.exception()).isEmpty());
    }

    @Test void expectedPhysicalDisagreementRemainsAmbiguousDespiteCalculatorAdoptingCount() {
        var f = scenario("93", true); var c = candidate(f);
        assertEquals(95, c.expectedStock().intValue()); assertEquals(93, c.physicalStock().intValue());
        assertEquals(93, tools.operationalContext(f.exception()).context().stock().expectedStock().intValue());
        assertFalse(c.eligible()); assertNull(c.targetStock());
        assertThrows(ConflictException.class, () -> correct(f, "ambiguous")); verify(adapter, never()).dispatch(any());
    }

    @Test void toleranceUsesExpectedAsTheSingleTargetAndSystemAlreadyEqualIsRejected() {
        var f = scenario("94", true); jdbc.update("update demo_configuration set physical_stock_tolerance=1 where id=1");
        assertEquals(95, candidate(f).targetStock().intValue());
        var g = scenario("95", true); event(g, "STOCK_UPDATED", null, "95", false, clock.instant());
        assertFalse(candidate(g).eligible()); assertThrows(ConflictException.class, () -> correct(g, "already-correct"));
    }

    @Test void absentOrUnconfirmedCountAndMissingTargetChannelObservationAreRejected() {
        for (var f : List.of(scenario(null, false), scenario("95", false))) {
            assertFalse(candidate(f).eligible()); assertThrows(ConflictException.class, () -> correct(f, "no-count"));
        }
        var f = scenario("95", true);
        var other = products.addMapping(f.product(), new ProductService.MappingInput("other", "OTHER-" + f.product(), MappingStatus.ACTIVE)).value();
        var c = tools.operationalContext(f.exception()).correctionCandidates().stream().filter(row -> row.mappingId().equals(other.id())).findFirst().orElseThrow();
        assertFalse(c.eligible()); assertNull(c.systemStock());
    }

    @Test void inactiveAndWrongProductMappingsAndChangedExternalIdentityCannotExecute() {
        var f = scenario("95", true); var fp = candidate(f).decisionFingerprint();
        products.updateMapping(f.mapping().id(), new ProductService.MappingInput(f.mapping().channel(), f.mapping().externalId(), MappingStatus.INACTIVE), f.mapping().version());
        assertThrows(ConflictException.class, () -> corrections.correct(f.exception(), f.mapping().id(), key(f, "inactive"), fp));
        var g = scenario("95", true);
        assertThrows(ConflictException.class, () -> corrections.correct(g.exception(), f.mapping().id(), "wrong-product", candidate(g).decisionFingerprint()));
        var reviewed = candidate(g).decisionFingerprint();
        products.updateMapping(g.mapping().id(), new ProductService.MappingInput(g.mapping().channel(), "REPLACED-" + g.product(), MappingStatus.ACTIVE), g.mapping().version());
        assertThrows(ConflictException.class, () -> corrections.correct(g.exception(), g.mapping().id(), "changed-identity", reviewed));
        verify(adapter, never()).dispatch(any());
    }

    @Test void staleFingerprintNewMovementNewCheckpointAndLateMovementAreRejected() {
        for (String type : List.of("SALE_CONFIRMED", "PHYSICAL_COUNT", "STOCK_ADJUSTED")) {
            var f = scenario("95", true); var fp = candidate(f).decisionFingerprint();
            event(f, type, type.equals("PHYSICAL_COUNT") ? "95" : "1", null, type.equals("PHYSICAL_COUNT"),
                type.equals("STOCK_ADJUSTED") ? NOW.minusSeconds(25) : clock.instant());
            assertThrows(ConflictException.class, () -> corrections.correct(f.exception(), f.mapping().id(), "stale-" + type, fp));
        }
        var f = scenario("95", true); assertThrows(ConflictException.class,
            () -> corrections.correct(f.exception(), f.mapping().id(), key(f, "fake-fp"), "0".repeat(64)));
        verify(adapter, never()).dispatch(any());
    }

    @Test void sameKeyReplaysAndDifferentKeyCannotCauseSecondEffectBeforeOrAfterVerification() {
        var f = scenario("95", true); var fp = candidate(f).decisionFingerprint();
        var first = corrections.correct(f.exception(), f.mapping().id(), key(f, "replay"), fp);
        assertTrue(corrections.correct(f.exception(), f.mapping().id(), " " + key(f, "replay") + " ", fp).replayed());
        assertThrows(ConflictException.class, () -> corrections.correct(f.exception(), f.mapping().id(), key(f, "different"), fp));
        clock.set(clock.instant().plusSeconds(1)); confirm(f, first.command(), first.command().orderId(), "95", clock.instant());
        evaluator.evaluate(clock.instant());
        var replay = corrections.correct(f.exception(), f.mapping().id(), key(f, "replay"), fp);
        assertEquals(first.actionId(), replay.actionId()); assertEquals("VERIFIED", replay.verificationStatus());
        verify(adapter, times(1)).dispatch(any()); assertEquals(1, commands.get(first.command().id()).attempts().size());
    }

    @Test void concurrentSameKeyAndDifferentKeysSerializeToOneExternalEffect() throws Exception {
        for (boolean sameKey : List.of(true, false)) {
            var f = scenario("95", true); var fp = candidate(f).decisionFingerprint(); var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> { start.await(); return rpc(arguments(f, "race-a", fp)); });
                var b = pool.submit(() -> { start.await(); return rpc(arguments(f, sameKey ? "race-a" : "race-b", fp)); });
                start.countDown(); var ar = a.get(15, TimeUnit.SECONDS); var br = b.get(15, TimeUnit.SECONDS);
                if (sameKey) assertEquals(ar.path("result").path("structuredContent").path("actionId"), br.path("result").path("structuredContent").path("actionId"));
                else assertNotEquals(ar.path("result").path("isError").asBoolean(), br.path("result").path("isError").asBoolean());
            }
            assertEquals(1, actions.findByExceptionIdOrderByStartedAtDesc(f.exception()).size());
        }
        verify(adapter, times(2)).dispatch(any());
    }

    @Test void adapterRejectionAndThrownFailureAreAuditedFailedWithNoProofOrDestinationEffect() {
        for (boolean thrown : List.of(false, true)) {
            if (thrown) doThrow(new IllegalStateException("Private adapter details")).when(adapter).dispatch(any());
            else doReturn(new OutboundCommandAdapter.DispatchResult(false, null, "SIMULATED_FAILURE", "Adapter rejected")).when(adapter).dispatch(any());
            var f = scenario("95", true); var result = correct(f, "fail-" + f.product());
            assertEquals("FAILED", result.executionStatus()); assertEquals("FAILED", result.verificationStatus());
            assertEquals("FAILED", result.command().attempts().getFirst().result());
            evaluator.evaluate(clock.instant()); assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exception()).status());
            assertNull(exceptions.get(f.exception()).reconciliationEventId());
            assertEquals(0, jdbc.queryForObject("select count(*) from mock_inventory_correction where command_id=?", Integer.class, result.command().id()));
            assertFalse(result.command().attempts().getFirst().errorMessage().contains("Private"));
        }
    }

    @Test void missingConfirmationAndLateConfirmationCannotFabricateResolution() {
        var f = scenario("95", true); var result = correct(f, "timeout");
        assertEquals("PENDING_VERIFICATION", result.verificationStatus());
        clock.set(result.command().deadlineAt().plusSeconds(1)); evaluator.evaluate(clock.instant());
        assertEquals(CommandStatus.TIMED_OUT, commands.get(result.command().id()).status());
        assertEquals("FAILED", actions.findById(result.actionId()).orElseThrow().verificationStatus);
        confirm(f, result.command(), result.command().orderId(), "95", clock.instant()); evaluator.evaluate(clock.instant());
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exception()).status()); assertNull(exceptions.get(f.exception()).reconciliationEventId());
    }

    @Test void wrongTargetOrderOrSourceAndBackdatedConfirmationDoNotVerify() {
        for (String mismatch : List.of("quantity", "order", "source", "timestamp")) {
            var f = scenario("95", true); var result = correct(f, "mismatch-" + mismatch + f.product());
            clock.set(clock.instant().plusSeconds(1));
            if (mismatch.equals("source")) {
                events.ingest(new EventInput("unrelated-source", UUID.randomUUID().toString(), "STOCK_UPDATED", clock.instant(),
                    f.product(), f.sku(), null, result.command().orderId(), null, null, null, null, new BigDecimal("95"), null, Map.of()));
            } else confirm(f, result.command(), mismatch.equals("order") ? "OTHER" : result.command().orderId(),
                mismatch.equals("quantity") ? "94" : "95", mismatch.equals("timestamp") ? result.command().requestedAt().minusSeconds(1) : clock.instant());
            assertEquals(CommandStatus.PENDING_CONFIRMATION, commands.get(result.command().id()).status());
            evaluator.evaluate(clock.instant()); assertNotEquals("VERIFIED", actions.findById(result.actionId()).orElseThrow().verificationStatus);
            assertNull(exceptions.get(f.exception()).reconciliationEventId());
        }
    }

    @Test void matchingConfirmationNeedsSeparateEngineProofAndPreservesOriginalEvidenceAndEvents() {
        var f = scenario("95", true); var original = exceptions.get(f.exception()).evidence(); var originalEvents = events.forSku(f.sku());
        var result = correct(f, "proof"); clock.set(clock.instant().plusSeconds(1));
        var confirmation = confirm(f, result.command(), result.command().orderId(), "95", clock.instant());
        assertEquals(CommandStatus.CONFIRMED, commands.get(result.command().id()).status());
        assertEquals("PENDING_VERIFICATION", tools.commandStatus(result.command().id()).agentActions().getFirst().verificationStatus());
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exception()).status());
        evaluator.evaluate(clock.instant());
        var alert = exceptions.get(f.exception()); assertEquals(ExceptionStatus.RESOLVED, alert.status());
        assertEquals(confirmation.id(), alert.reconciliationEventId()); assertTrue(alert.evidence().containsAll(original));
        assertEquals(original.size() + 1, alert.evidence().size()); assertEquals("verifiedCorrectedStock", alert.evidence().getLast().label());
        assertTrue(events.forSku(f.sku()).containsAll(originalEvents)); assertEquals(4, events.forSku(f.sku()).size());
        assertEquals("VERIFIED", actionViews.forException(f.exception()).getFirst().verificationStatus());
        evaluator.evaluate(clock.instant().plusSeconds(1)); assertEquals(original.size() + 1, exceptions.get(f.exception()).evidence().size());
    }

    @Test void newMovementCheckpointMappingOrConfigInvalidatesPendingAndConfirmedExecution() {
        for (String change : List.of("movement", "checkpoint", "mapping", "config")) {
            var f = scenario("95", true); var result = correct(f, "changed-" + change + f.product());
            clock.set(clock.instant().plusSeconds(1)); confirm(f, result.command(), result.command().orderId(), "95", clock.instant());
            clock.set(clock.instant().plusSeconds(1));
            switch (change) {
                case "movement" -> event(f, "SALE_CONFIRMED", "1", null, false, clock.instant());
                case "checkpoint" -> event(f, "PHYSICAL_COUNT", "95", null, true, clock.instant());
                case "mapping" -> products.updateMapping(f.mapping().id(), new ProductService.MappingInput(f.mapping().channel(), f.mapping().externalId(), MappingStatus.INACTIVE), f.mapping().version());
                case "config" -> jdbc.update("update demo_configuration set physical_stock_tolerance=2 where id=1");
            }
            evaluator.evaluate(clock.instant()); assertEquals("FAILED", actions.findById(result.actionId()).orElseThrow().verificationStatus);
            assertNull(exceptions.get(f.exception()).reconciliationEventId());
            jdbc.update("update demo_configuration set physical_stock_tolerance=0 where id=1");
        }
    }

    @Test void genericCreationAndRetryCannotBypassGuardAndE03E04StayReadOnly() {
        var f = scenario("95", true); var result = correct(f, "bounded");
        assertThrows(ConflictException.class, () -> commands.create(CommandKind.INVENTORY_CORRECTION,
            new CommandService.CommandInput(f.count(), f.mapping().channel(), "generic", false)));
        assertThrows(ConflictException.class, () -> commands.retry(result.command().id(), "generic-retry", false));
        for (var code : List.of(ExceptionCode.E03, ExceptionCode.E04)) {
            var alert = exceptions.create(new ExceptionService.ExceptionDraft(code, f.count(), null, Severity.WARNING,
                "Read only", f.product(), f.sku(), null, "Expected", "Observed", "test", "Impact", "Investigate",
                List.of(new ExceptionService.EvidenceDraft(f.count(), "SOURCE", "test", "event", "count", NOW))));
            assertTrue(tools.operationalContext(alert.id()).correctionCandidates().isEmpty());
            assertThrows(ConflictException.class, () -> corrections.correct(alert.id(), f.mapping().id(), "forbidden-" + code, candidate(f).decisionFingerprint()));
        }
        verify(adapter, times(1)).dispatch(any());
    }

    @Test void manualResolutionAndBackdatedEvaluationAfterDeadlineAreNotProof() {
        var f = scenario("95", true); var result = correct(f, "manual");
        exceptions.resolve(f.exception(), "Manual acknowledgement"); evaluator.evaluate(clock.instant());
        assertEquals("FAILED", actions.findById(result.actionId()).orElseThrow().verificationStatus);
        assertNull(exceptions.get(f.exception()).reconciliationEventId());
        var g = scenario("95", true); var pending = correct(g, "backdated");
        clock.set(pending.command().deadlineAt().plusSeconds(1)); evaluator.evaluate(pending.command().requestedAt().plusSeconds(1));
        assertEquals("FAILED", actions.findById(pending.actionId()).orElseThrow().verificationStatus);
    }

    @Test void pendingEquivalentSyncOrChangedCommandContextCannotAuthorizeCorrection() {
        var f = scenario("95", true); var reviewed = candidate(f).decisionFingerprint();
        var sync = commands.forProduct(f.product(), CommandKind.INVENTORY_SYNC).getFirst();
        commands.retry(sync.id(), "retry-" + f.product(), false);
        assertFalse(candidate(f).eligible());
        assertThrows(ConflictException.class, () -> corrections.correct(f.exception(), f.mapping().id(), key(f, "overlap"), reviewed));
        verify(adapter, never()).dispatch(any());
    }

    @Test void equivalentCheckpointCannotBeCorrectedThroughAnotherException() {
        var f = scenario("95", true); correct(f, "one-checkpoint");
        var alert = exceptions.create(new ExceptionService.ExceptionDraft(ExceptionCode.E02, f.count(), null, Severity.WARNING,
            "System divergence", f.product(), f.sku(), null, "95", "100", "test", "Impact", "Investigate",
            List.of(new ExceptionService.EvidenceDraft(f.count(), "SOURCE", "test", "event", "count", NOW))));
        var c = tools.operationalContext(alert.id()).correctionCandidates().getFirst(); assertFalse(c.eligible());
        assertThrows(ConflictException.class, () -> corrections.correct(alert.id(), f.mapping().id(), key(f, "other-alert"), c.decisionFingerprint()));
        verify(adapter, times(1)).dispatch(any());
    }

    @Test void e02CanCorrectOnlyAfterFreshCountAndIndependentMovementsAgree() {
        var f = scenario("93", true); evaluator.evaluate(clock.instant());
        var e02 = exceptions.forProduct(f.product()).stream().filter(e -> e.code() == ExceptionCode.E02).findFirst().orElseThrow();
        clock.set(clock.instant().plusSeconds(1)); event(f, "STOCK_ADJUSTED", "2", null, false, clock.instant());
        clock.set(clock.instant().plusSeconds(1)); event(f, "PHYSICAL_COUNT", "95", null, true, clock.instant());
        var c = tools.operationalContext(e02.id()).correctionCandidates().getFirst(); assertTrue(c.eligible(), c.reason());
        var result = corrections.correct(e02.id(), f.mapping().id(), key(f, "e02-stock"), c.decisionFingerprint());
        clock.set(clock.instant().plusSeconds(1)); confirm(f, result.command(), result.command().orderId(), "95", clock.instant());
        evaluator.evaluate(clock.instant()); assertEquals(ExceptionStatus.RESOLVED, exceptions.get(e02.id()).status());
        assertEquals("VERIFIED", actions.findById(result.actionId()).orElseThrow().verificationStatus);
    }

    @Test void concurrentEvaluationsAppendExactlyOneCorrectionProof() throws Exception {
        var f = scenario("95", true); var originals = exceptions.get(f.exception()).evidence().size();
        var result = correct(f, "evaluation-race"); clock.set(clock.instant().plusSeconds(1));
        confirm(f, result.command(), result.command().orderId(), "95", clock.instant());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Object> evaluate = () -> { start.await(); return evaluator.evaluate(clock.instant()); };
            var a = pool.submit(evaluate); var b = pool.submit(evaluate); start.countDown();
            a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        }
        assertEquals(originals + 1, exceptions.get(f.exception()).evidence().size());
        assertEquals("VERIFIED", actions.findById(result.actionId()).orElseThrow().verificationStatus);
    }

    @Test void demoResetRemovesOnlyOwnedCorrectionEffectsAndKeepsOtherProducts() {
        var other = scenario("95", true); var otherResult = correct(other, "unrelated");
        String run = "QA-" + UUID.randomUUID().toString().replace("-", "");
        var f = scenario("95", true, run); var result = correct(f, "owned");
        reset.reset(run);
        assertFalse(actions.existsById(result.actionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from mock_inventory_correction where command_id=?", Integer.class, result.command().id()));
        assertEquals(1, jdbc.queryForObject("select count(*) from mock_inventory_correction where command_id=?", Integer.class, otherResult.command().id()));
    }
}
