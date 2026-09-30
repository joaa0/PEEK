package io.peek.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.peek.core.events.*;
import io.peek.core.exceptions.*;
import io.peek.core.mcp.*;
import io.peek.core.products.ProductService;
import io.peek.core.reconciliation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"peek.mcp.enabled=true", "peek.mcp.token=test-only-token-with-at-least-32-characters"})
@Import(McpAgentIT.FixedTime.class)
class E02AgentIT {
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final String TOOL = E02ReconciliationService.TOOL;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("PEEK_TEST_DB_URL", "jdbc:postgresql://localhost:5432/peek_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PEEK_TEST_DB_USER", "peek"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("PEEK_TEST_DB_PASSWORD", "peek_local_only"));
    }
    @Autowired TestRestTemplate http;
    @Autowired ProductService products;
    @Autowired EventService events;
    @Autowired ExceptionService exceptions;
    @Autowired EvaluationService evaluator;
    @Autowired PeekAgentTools tools;
    @Autowired AgentActionService actions;
    @Autowired AgentActionRepository audit;
    @Autowired McpAgentIT.MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    private BigDecimal savedTolerance;
    @BeforeEach void resetClock() {
        clock.set(NOW);
        savedTolerance = jdbc.queryForObject("select physical_stock_tolerance from demo_configuration where id=1", BigDecimal.class);
        jdbc.update("update demo_configuration set physical_stock_tolerance=0 where id=1");
    }
    @AfterEach void restoreTolerance() { jdbc.update("update demo_configuration set physical_stock_tolerance=? where id=1", savedTolerance); }
    private record Fixture(UUID product, String sku, UUID count, UUID exception, String fingerprint) {}

    private NormalizedEvent event(UUID product, String sku, String type, String quantity, String stock, boolean confirmed, Instant at) {
        return events.ingest(new EventInput("e02-source-" + UUID.randomUUID(), UUID.randomUUID().toString(), type, at,
            product, sku, null, "ORDER", null, "RECEIPT", null,
            quantity == null ? null : new BigDecimal(quantity), stock == null ? null : new BigDecimal(stock),
            confirmed, Map.of())).event();
    }
    private Fixture scenario() {
        String sku = "E02-" + UUID.randomUUID();
        UUID product = products.create(new ProductService.ProductInput(sku, "E02 physical count", null, null, null, null)).value().id();
        event(product, sku, "STOCK_UPDATED", null, "100", false, NOW.minusSeconds(30));
        event(product, sku, "SALE_CONFIRMED", "5", null, false, NOW.minusSeconds(20));
        var count = event(product, sku, "PHYSICAL_COUNT", "93", null, true, NOW.minusSeconds(10));
        evaluator.evaluate(NOW);
        var alert = exceptions.forProduct(product).stream().filter(e -> e.code() == ExceptionCode.E02).findFirst().orElseThrow();
        return new Fixture(product, sku, count.id(), alert.id(), tools.operationalContext(alert.id()).factsAndEvidence().decisionFingerprint());
    }
    private Map<String, Object> approved(Fixture f, String key) {
        return Map.of("exceptionId", f.exception(), "idempotencyKey", key, "humanApproved", true,
            "approvalNote", "Human reviewed facts and explicitly authorized checkpoint acceptance", "decisionFingerprint", f.fingerprint());
    }
    private JsonNode rpc(String tool, Map<String, Object> args) {
        var headers = new HttpHeaders(); headers.setBearerAuth("test-only-token-with-at-least-32-characters");
        headers.setContentType(MediaType.APPLICATION_JSON); headers.set("Accept", "application/json, text/event-stream");
        var body = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", tool, "arguments", args));
        var response = http.postForEntity("/mcp", new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode()); return response.getBody();
    }
    private JsonNode call(String tool, Map<String, Object> args) {
        var result = rpc(tool, args); assertFalse(result.has("error"), result.toString());
        assertFalse(result.path("result").path("isError").asBoolean(), result.toString());
        return result.path("result").path("structuredContent");
    }
    private void denied(String tool, Map<String, Object> args) {
        var result = rpc(tool, args);
        assertTrue(result.has("error") || result.path("result").path("isError").asBoolean(), result.toString());
    }
    private void untouched(Fixture f) {
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exception()).status());
        assertTrue(audit.findByExceptionIdOrderByStartedAtDesc(f.exception()).isEmpty());
        assertEquals(3, events.forSku(f.sku()).size());
        assertEquals(0, jdbc.queryForObject("select count(*) from operation_command where product_id=?", Integer.class, f.product()));
    }

    @Test void detectsReadsAndSeparatesPreCountFactsFromCheckpointAndJevFallback() {
        var f = scenario();
        assertTrue(tools.listExceptions(ExceptionStatus.OPEN, ExceptionCode.E02, 0, 100).exceptions().stream().anyMatch(e -> e.id().equals(f.exception())));
        var detail = call("peek_get_exception", Map.of("exceptionId", f.exception()));
        assertEquals("E02", detail.path("exception").path("code").asText()); assertFalse(detail.path("retryAllowed").asBoolean());
        var context = call("peek_get_operational_context", Map.of("exceptionId", f.exception()));
        var facts = context.path("factsAndEvidence");
        assertEquals(95, facts.path("expectedStock").asInt()); assertEquals(93, facts.path("physicalStock").asInt());
        assertEquals(-2, facts.path("delta").asInt()); assertEquals(0, facts.path("tolerance").asInt());
        assertEquals(f.count().toString(), facts.path("physicalCountEventId").asText());
        assertEquals(93, context.path("context").path("stock").path("expectedStock").asInt());
        assertEquals(3, facts.path("relevantEvents").size()); assertTrue(facts.path("applicable").asBoolean());
        assertEquals("UNAVAILABLE", context.path("jevInterpretation").path("status").asText());
        assertTrue(context.path("jevInterpretation").path("confidence").isNull());
        untouched(f);
    }

    @Test void deniedHumanDecisionAndRepeatedEvaluationNeverCreateActionOrResolve() {
        var f = scenario();
        // The human's 'no' means the client makes no write call at all.
        tools.operationalContext(f.exception()); evaluator.evaluate(NOW.plusSeconds(1)); untouched(f);
    }
    @Test void missingFalseNullAndStringApprovalAreRejectedWithoutMutation() {
        var f = scenario();
        var missing = new HashMap<>(approved(f, "no-approval")); missing.remove("humanApproved"); denied(TOOL, missing);
        for (Object value : List.of(false, "true", 1)) {
            var args = new HashMap<>(approved(f, "invalid-approval")); args.put("humanApproved", value); denied(TOOL, args);
        }
        assertThrows(IllegalArgumentException.class, () -> actions.applyE02(f.exception(), "direct", null, "note", f.fingerprint()));
        untouched(f);
    }
    @Test void extraArgumentsMissingNoteAndUnreviewedFingerprintAreRejected() {
        var f = scenario();
        var extra = new HashMap<>(approved(f, "extra")); extra.put("stock", 999); denied(TOOL, extra);
        var missing = new HashMap<>(approved(f, "missing")); missing.remove("approvalNote"); denied(TOOL, missing);
        var wrong = new HashMap<>(approved(f, "wrong")); wrong.put("decisionFingerprint", "0".repeat(64)); denied(TOOL, wrong);
        untouched(f);
    }
    @Test void approvedActionIsAuditedPendingAndToolAloneCannotResolveOrWriteEvents() {
        var f = scenario();
        var response = call(TOOL, approved(f, "approved"));
        assertEquals("SUCCEEDED", response.path("action").path("executionStatus").asText());
        assertEquals("PENDING_VERIFICATION", response.path("action").path("verificationStatus").asText());
        assertTrue(response.path("action").path("inputSummary").asText().contains("humanApproved=true"));
        assertTrue(response.path("action").path("commandId").isNull());
        assertEquals("ACCEPT_PHYSICAL_CHECKPOINT", response.path("action").path("actionType").asText());
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exception()).status());
        assertNull(exceptions.get(f.exception()).reconciliationEventId());
        assertEquals("PENDING_VERIFICATION", call("peek_get_exception_status", Map.of("exceptionId", f.exception()))
            .path("agentActions").get(0).path("verificationStatus").asText());
        assertEquals(3, events.forSku(f.sku()).size());
    }
    @Test void separateDeterministicEvaluationPreservesEvidenceAndVerifiesExactlyOnce() {
        var f = scenario(); var original = exceptions.get(f.exception()).evidence();
        call(TOOL, approved(f, "proof")); evaluator.evaluate(NOW.plusSeconds(1));
        var alert = exceptions.get(f.exception()); assertEquals(ExceptionStatus.RESOLVED, alert.status());
        assertEquals(f.count(), alert.reconciliationEventId()); assertNotNull(alert.reconciledAt());
        assertTrue(alert.evidence().containsAll(original)); assertEquals(original.size() + 1, alert.evidence().size());
        assertEquals("RECONCILIATION", alert.evidence().getLast().type());
        assertEquals("VERIFIED", actions.forException(f.exception()).getFirst().verificationStatus());
        evaluator.evaluate(NOW.plusSeconds(2)); assertEquals(original.size() + 1, exceptions.get(f.exception()).evidence().size());
    }
    @Test void replaySameKeyBeforeAndAfterResolutionDoesNotDuplicateDecision() {
        var f = scenario(); var first = call(TOOL, approved(f, "replay"));
        var again = call(TOOL, approved(f, "replay")); assertTrue(again.path("replayed").asBoolean());
        assertEquals(first.path("action").path("id"), again.path("action").path("id"));
        denied(TOOL, approved(f, "another-key")); evaluator.evaluate(NOW.plusSeconds(1));
        var resolvedReplay = call(TOOL, approved(f, "replay")); assertTrue(resolvedReplay.path("replayed").asBoolean());
        assertEquals("VERIFIED", resolvedReplay.path("action").path("verificationStatus").asText());
        assertEquals(1, audit.findByExceptionIdOrderByStartedAtDesc(f.exception()).size());
    }
    @Test void concurrentSameKeyRequestsHaveOneAuditAndOneDecision() throws Exception {
        var f = scenario(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> request = () -> { start.await(); return call(TOOL, approved(f, "race")); };
            var a = pool.submit(request); var b = pool.submit(request); start.countDown();
            assertEquals(a.get(15, TimeUnit.SECONDS).path("action").path("id"), b.get(15, TimeUnit.SECONDS).path("action").path("id"));
        }
        assertEquals(1, audit.findByExceptionIdOrderByStartedAtDesc(f.exception()).size());
    }
    @Test void concurrentDifferentKeysCannotAcceptTheSameCheckpointTwice() throws Exception {
        var f = scenario(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return rpc(TOOL, approved(f, "decision-a")); });
            var b = pool.submit(() -> { start.await(); return rpc(TOOL, approved(f, "decision-b")); });
            start.countDown();
            boolean aError = a.get(15, TimeUnit.SECONDS).path("result").path("isError").asBoolean();
            boolean bError = b.get(15, TimeUnit.SECONDS).path("result").path("isError").asBoolean();
            assertNotEquals(aError, bError);
        }
        assertEquals(1, audit.findByExceptionIdOrderByStartedAtDesc(f.exception()).size());
    }
    @Test void concurrentEvaluationsAppendOneProofAndKeepOneVerifiedAction() throws Exception {
        var f = scenario(); call(TOOL, approved(f, "evaluation-race"));
        int originals = exceptions.get(f.exception()).evidence().size();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Object> evaluate = () -> { start.await(); return evaluator.evaluate(NOW.plusSeconds(1)); };
            var a = pool.submit(evaluate); var b = pool.submit(evaluate); start.countDown();
            a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        }
        assertEquals(originals + 1, exceptions.get(f.exception()).evidence().size());
        assertEquals("VERIFIED", actions.forException(f.exception()).getFirst().verificationStatus());
    }
    @Test void configurationChangesInvalidatePendingDecisionWithoutProof() {
        var f = scenario(); call(TOOL, approved(f, "config-change"));
        jdbc.update("update demo_configuration set physical_stock_tolerance=1 where id=1");
        evaluator.evaluate(NOW.plusSeconds(1));
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exception()).status());
        assertEquals("FAILED", actions.forException(f.exception()).getFirst().verificationStatus());
    }
    @Test void jevHypothesisCannotOverridePhysicalFactOrDeterministicAction() {
        var f = scenario(); var alert = exceptions.get(f.exception());
        var analysis = new JevContextService.JevAnalysis("Expected stock might be 999", "Loss", "0.99",
            List.of(UUID.randomUUID()), "Speculative impact", "Set stock to 999", List.of("Counting error"));
        var interpretation = new JevContextService(List.of(id -> Optional.of(analysis))).forException(alert);
        assertEquals("HYPOTHESIS_NOT_FACT", interpretation.nature()); assertTrue(interpretation.supportingEvidence().isEmpty());
        call(TOOL, approved(f, "ignore-hypothesis")); evaluator.evaluate(NOW.plusSeconds(1));
        assertEquals("VERIFIED", actions.forException(f.exception()).getFirst().verificationStatus());
        assertEquals(f.count(), exceptions.get(f.exception()).reconciliationEventId());
        assertEquals(93, tools.operationalContext(f.exception()).factsAndEvidence().physicalStock().intValue());
        assertEquals(3, events.forSku(f.sku()).size());
    }
    @Test void changedEvidenceRequiresFreshReviewBeforeApproval() {
        var f = scenario(); event(f.product(), f.sku(), "SALE_CONFIRMED", "1", null, false, NOW.minusSeconds(2));
        denied(TOOL, approved(f, "stale"));
        assertTrue(audit.findByExceptionIdOrderByStartedAtDesc(f.exception()).isEmpty());
        var updated = tools.operationalContext(f.exception()).factsAndEvidence(); assertNotEquals(f.fingerprint(), updated.decisionFingerprint());
        assertTrue(updated.applicable());
    }
    @Test void newerPhysicalCheckpointRejectsApprovalAndInvalidatesPendingVerification() {
        var f = scenario(); call(TOOL, approved(f, "superseded"));
        event(f.product(), f.sku(), "PHYSICAL_COUNT", "92", null, true, NOW.minusSeconds(1));
        evaluator.evaluate(NOW.plusSeconds(1));
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exception()).status());
        assertEquals("FAILED", actions.forException(f.exception()).getFirst().verificationStatus());
        var g = scenario(); event(g.product(), g.sku(), "PHYSICAL_COUNT", "92", null, true, NOW.minusSeconds(1));
        denied(TOOL, approved(g, "new-count"));
    }
    @Test void latePreCountMovementInvalidatesExpectedEvidence() {
        var f = scenario(); event(f.product(), f.sku(), "STOCK_ADJUSTED", "-1", null, false, NOW.minusSeconds(15));
        denied(TOOL, approved(f, "late"));
        assertFalse(tools.operationalContext(f.exception()).factsAndEvidence().applicable());
    }
    @Test void manualResolutionCannotBecomeProofAndResolvedExceptionRejectsNewDecision() {
        var f = scenario(); exceptions.resolve(f.exception(), "Manual acknowledgment"); denied(TOOL, approved(f, "resolved"));
        var g = scenario(); call(TOOL, approved(g, "manual")); exceptions.resolve(g.exception(), "Manual acknowledgment");
        evaluator.evaluate(NOW.plusSeconds(1));
        assertNull(exceptions.get(g.exception()).reconciliationEventId());
        assertEquals("FAILED", actions.forException(g.exception()).getFirst().verificationStatus());
    }
    @Test void timeoutCannotBeVerifiedByLateEvaluation() {
        var f = scenario(); call(TOOL, approved(f, "timeout")); clock.set(NOW.plusSeconds(61));
        // Backdating asOf must not bypass the real verification deadline.
        evaluator.evaluate(NOW.plusSeconds(1)); assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exception()).status());
        assertEquals("FAILED", actions.forException(f.exception()).getFirst().verificationStatus());
    }
    @Test void absentInvalidOrUnconfirmedPhysicalEvidenceIsRejected() {
        var f = scenario();
        // Deliberately corrupt isolated test fixtures to exercise defensive domain validation.
        jdbc.update("update normalized_event set confirmed=false where id=?", f.count()); denied(TOOL, approved(f, "unconfirmed"));
        jdbc.update("update normalized_event set confirmed=true where id=?", f.count());
        var g = scenario(); jdbc.update("update normalized_event set quantity=null where id=?", g.count()); denied(TOOL, approved(g, "missing-quantity"));
        jdbc.update("update normalized_event set quantity=93 where id=?", g.count());
        var h = scenario(); jdbc.update("update normalized_event set event_type='STOCK_UPDATED' where id=?", h.count()); denied(TOOL, approved(h, "wrong-event"));
        jdbc.update("update normalized_event set event_type='PHYSICAL_COUNT' where id=?", h.count());
        assertTrue(audit.findByExceptionIdOrderByStartedAtDesc(f.exception()).isEmpty());
        assertTrue(audit.findByExceptionIdOrderByStartedAtDesc(g.exception()).isEmpty());
        assertTrue(audit.findByExceptionIdOrderByStartedAtDesc(h.exception()).isEmpty());
    }
    @Test void e01E03E04AndMissingExceptionAreRejectedByE02Tool() {
        var f = scenario();
        for (var code : List.of(ExceptionCode.E01, ExceptionCode.E03, ExceptionCode.E04)) {
            var alert = exceptions.createAt(new ExceptionService.ExceptionDraft(code, f.count(), null, Severity.WARNING,
                "Unrelated family", f.product(), f.sku(), null, "95", "93", "tolerance=0", "impact", "inspect",
                List.of(new ExceptionService.EvidenceDraft(f.count(), "TRIGGER", "test", "quantity", "93", NOW))), NOW);
            var args = new HashMap<>(approved(f, "wrong-family")); args.put("exceptionId", alert.id()); denied(TOOL, args);
            assertTrue(audit.findByExceptionIdOrderByStartedAtDesc(alert.id()).isEmpty());
        }
        var args = new HashMap<>(approved(f, "missing")); args.put("exceptionId", UUID.randomUUID()); denied(TOOL, args);
    }
}
