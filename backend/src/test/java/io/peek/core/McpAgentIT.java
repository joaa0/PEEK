package io.peek.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.peek.core.events.EventInput;
import io.peek.core.events.EventService;
import io.peek.core.exceptions.ExceptionCode;
import io.peek.core.exceptions.ExceptionService;
import io.peek.core.exceptions.ExceptionStatus;
import io.peek.core.integrations.MockInventoryAdapter;
import io.peek.core.mcp.AgentActionRepository;
import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.CommandService;
import io.peek.core.orchestration.CommandStatus;
import io.peek.core.products.MappingStatus;
import io.peek.core.products.ProductService;
import io.peek.core.reconciliation.EvaluationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"peek.mcp.enabled=true", "peek.mcp.token=test-only-token-with-at-least-32-characters"})
@Import(McpAgentIT.FixedTime.class)
class McpAgentIT {
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final String TOKEN = "test-only-token-with-at-least-32-characters";
    @TestConfiguration static class FixedTime {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private final java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        void set(Instant at) { now.set(at); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
    @Autowired MutableClock testClock;
    @BeforeEach void resetTime() { testClock.set(NOW); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("PEEK_TEST_DB_URL", "jdbc:postgresql://localhost:5432/peek_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PEEK_TEST_DB_USER", "peek"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("PEEK_TEST_DB_PASSWORD", "peek_local_only"));
    }

    @Autowired TestRestTemplate http;
    @Autowired ProductService products;
    @Autowired EventService events;
    @Autowired CommandService commands;
    @Autowired ExceptionService exceptions;
    @Autowired EvaluationService evaluator;
    @Autowired AgentActionRepository audit;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private record Fixture(UUID productId, String sku, String channel, String externalItem,
                           CommandService.CommandView command, UUID exceptionId) {}

    private Fixture scenario(boolean createException) {
        String suffix = UUID.randomUUID().toString();
        String sku = "MCP-" + suffix, channel = "inventory-" + suffix, item = "ITEM-" + suffix;
        UUID product = products.create(new ProductService.ProductInput(sku, "MCP demo", null, null, null, null)).value().id();
        products.addMapping(product, new ProductService.MappingInput(channel, item, MappingStatus.ACTIVE));
        events.ingest(new EventInput("baseline-" + suffix, "BASE", "STOCK_UPDATED", NOW.minusSeconds(20),
            product, sku, null, null, null, null, null, null, new BigDecimal("100"), null, Map.of()));
        var sale = events.ingest(new EventInput("sales-" + suffix, "SALE", "SALE_CONFIRMED", NOW.minusSeconds(1),
            product, sku, null, "ORDER-" + suffix, null, null, null, new BigDecimal("5"), null, null, Map.of())).event();
        var command = commands.create(CommandKind.INVENTORY_SYNC,
            new CommandService.CommandInput(sale.id(), channel, "sync-" + suffix, true)).command();
        UUID exceptionId = null;
        if (createException) {
            evaluator.evaluate(command.deadlineAt().plusSeconds(1));
            testClock.set(command.deadlineAt().plusSeconds(1));
            exceptionId = exceptions.forProduct(product).stream()
                .filter(row -> command.id().equals(row.operationCommandId())).findFirst().orElseThrow().id();
        }
        return new Fixture(product, sku, channel, item, command, exceptionId);
    }

    private JsonNode rpcResult(String name, Map<String, Object> arguments) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json, text/event-stream");
        headers.setBearerAuth(TOKEN);
        headers.set("MCP-Protocol-Version", "2025-06-18");
        var body = Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", "tools/call",
            "params", Map.of("name", name, "arguments", arguments));
        var response = http.postForEntity("/mcp", new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().has("error"), response.getBody().toString());
        return response.getBody().get("result");
    }

    private JsonNode call(String name, Map<String, Object> arguments) {
        var result = rpcResult(name, arguments);
        assertFalse(result.path("isError").asBoolean(), result.toString());
        return result.get("structuredContent");
    }

    private JsonNode retry(Fixture f, String key) {
        return call("peek_retry_inventory_sync", Map.of("commandId", f.command().id(), "idempotencyKey", key));
    }

    private void confirmation(Fixture f, String order, String amount) {
        var notice = new MockInventoryAdapter.StockNotice("CONFIRM-" + UUID.randomUUID(), f.channel(),
            f.externalItem(), order, null, new BigDecimal("5"), new BigDecimal(amount), testClock.instant().plusSeconds(1));
        assertEquals(HttpStatus.CREATED, http.postForEntity("/api/v1/mock/inventory", notice, Map.class).getStatusCode());
    }

    @Test void readsExistingEvidenceProductMappingsStockAndCommandHistory() throws Exception {
        var f = scenario(true);
        var detail = call("peek_get_exception", Map.of("exceptionId", f.exceptionId()));
        assertEquals("E01", detail.path("exception").path("code").asText());
        assertEquals("OPEN", detail.path("exception").path("status").asText());
        assertEquals(f.sku(), detail.path("exception").path("sku").asText());
        assertFalse(detail.path("exception").path("evidence").isEmpty());
        assertTrue(detail.path("retryAllowed").asBoolean());
        assertEquals(f.command().id().toString(), detail.path("command").path("id").asText());
        var context = call("peek_get_operational_context", Map.of("exceptionId", f.exceptionId()));
        var restContext = http.getForObject("/api/v1/products/" + f.productId() + "/context", JsonNode.class);
        assertTrue(restContext.equals((left, right) -> left.isNumber() && right.isNumber()
            ? left.decimalValue().compareTo(right.decimalValue()) : left.equals(right) ? 0 : 1,
            context.path("context")), "REST and MCP must expose the same operational data");
        assertEquals(0, context.path("context").path("stock").path("expectedStock").decimalValue().compareTo(new BigDecimal("95")));
        assertEquals(0, context.path("context").path("stock").path("systemStock").decimalValue().compareTo(new BigDecimal("100")));
        assertTrue(context.path("context").path("stock").path("physicalStock").isNull());
        assertEquals(1, context.path("relatedCommands").size());
    }

    @Test void e01RetryUsesExistingAttemptAndMockAndDoesNotResolveOnAcceptance() {
        var f = scenario(true);
        var result = retry(f, "mcp-retry");
        assertEquals("PENDING_CONFIRMATION", result.path("command").path("status").asText());
        assertEquals(2, result.path("command").path("attempts").size());
        assertTrue(result.path("command").path("attempts").get(1).path("externalRequestId").asText().startsWith("mock-inventory-"));
        assertEquals("PENDING_VERIFICATION", result.path("action").path("verificationStatus").asText());
        assertEquals("peek_retry_inventory_sync", result.path("action").path("toolName").asText());
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exceptionId()).status());
        assertEquals(1, audit.findByCommandIdOrderByStartedAtDesc(f.command().id()).size());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM operation_attempt WHERE command_id = ?", Integer.class, f.command().id()));
        assertEquals("PENDING_VERIFICATION", call("peek_get_command_status", Map.of("commandId", f.command().id()))
            .path("agentActions").get(0).path("verificationStatus").asText());
    }

    @Test void duplicateAndConcurrentCallsHaveOneEffectAndOneAudit() throws Exception {
        var f = scenario(true);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return retry(f, "concurrent-key"); });
            var second = executor.submit(() -> { start.await(); return retry(f, "concurrent-key"); });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(a.path("action").path("id"), b.path("action").path("id"));
            assertNotEquals(a.path("replayed").asBoolean(), b.path("replayed").asBoolean());
            assertEquals(2, commands.get(f.command().id()).attempts().size());
            assertEquals(1, audit.findByCommandIdOrderByStartedAtDesc(f.command().id()).size());
            var replay = retry(f, " concurrent-key ");
            assertTrue(replay.path("replayed").asBoolean());
            assertEquals(a.path("action").path("id"), replay.path("action").path("id"));
        } finally { executor.shutdownNow(); }
    }

    @Test void correlatedConfirmationAndEngineReconciliationProduceVerifiedWithEvidence() {
        var f = scenario(true);
        retry(f, "verify-key");
        confirmation(f, f.command().orderId(), "95");
        assertEquals(CommandStatus.CONFIRMED, commands.get(f.command().id()).status());
        // Confirmation alone is still not deterministic reconciliation.
        assertEquals("PENDING_VERIFICATION", call("peek_get_exception_status", Map.of("exceptionId", f.exceptionId()))
            .path("agentActions").get(0).path("verificationStatus").asText());
        evaluator.evaluate(testClock.instant().plusSeconds(2));
        var state = call("peek_get_exception_status", Map.of("exceptionId", f.exceptionId()));
        assertEquals("RESOLVED", state.path("status").asText());
        assertFalse(state.path("reconciliationEventId").isNull());
        assertEquals("VERIFIED", state.path("agentActions").get(0).path("verificationStatus").asText());
        var ex = exceptions.get(f.exceptionId());
        assertTrue(ex.evidence().stream().anyMatch(e -> "RECONCILIATION".equals(e.type())
            && commands.get(f.command().id()).confirmationEventId().equals(e.eventId())));
        int count = ex.evidence().size();
        evaluator.evaluate(testClock.instant().plusSeconds(3));
        assertEquals(count, exceptions.get(f.exceptionId()).evidence().size());
        assertTrue(retry(f, "verify-key").path("replayed").asBoolean());
        assertEquals("VERIFIED", audit.findByCommandIdOrderByStartedAtDesc(f.command().id()).getFirst().verificationStatus);
    }

    @Test void unrelatedAndWrongBalanceConfirmationsCannotProduceVerified() {
        var f = scenario(true);
        retry(f, "wrong-key");
        confirmation(f, "OTHER-ORDER", "95");
        assertEquals(CommandStatus.PENDING_CONFIRMATION, commands.get(f.command().id()).status());
        evaluator.evaluate(testClock.instant().plusSeconds(2));
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exceptionId()).status());
        confirmation(f, f.command().orderId(), "100");
        evaluator.evaluate(testClock.instant().plusSeconds(3));
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exceptionId()).status());
        assertNull(exceptions.get(f.exceptionId()).reconciliationEventId());
        assertNotEquals("VERIFIED", call("peek_get_exception_status", Map.of("exceptionId", f.exceptionId()))
            .path("agentActions").get(0).path("verificationStatus").asText());
    }

    @Test void noConfirmationTimeoutIsFailedAndDifferentKeyCannotRetryPendingCommand() {
        var f = scenario(true);
        retry(f, "timeout-key");
        assertTrue(rpcResult("peek_retry_inventory_sync", Map.of("commandId", f.command().id(),
            "idempotencyKey", "different-key")).path("isError").asBoolean());
        evaluator.evaluate(commands.get(f.command().id()).deadlineAt().plusSeconds(1));
        assertEquals("FAILED", call("peek_get_command_status", Map.of("commandId", f.command().id()))
            .path("agentActions").get(0).path("verificationStatus").asText());
        assertEquals(ExceptionStatus.OPEN, exceptions.get(f.exceptionId()).status());
    }

    @Test void manualResolveCannotClaimVerifiedOrPermitNewAction() {
        var f = scenario(true);
        retry(f, "manual-key");
        exceptions.resolve(f.exceptionId(), "Operator acknowledgement only");
        confirmation(f, f.command().orderId(), "95");
        evaluator.evaluate(testClock.instant().plusSeconds(2));
        var state = call("peek_get_exception_status", Map.of("exceptionId", f.exceptionId()));
        assertTrue(state.path("reconciliationEventId").isNull());
        assertEquals("PENDING_VERIFICATION", state.path("agentActions").get(0).path("verificationStatus").asText());
        assertTrue(rpcResult("peek_retry_inventory_sync", Map.of("commandId", f.command().id(),
            "idempotencyKey", "new-key")).path("isError").asBoolean());
    }

    @Test void noOpenE01AndFiscalCommandsAreDeniedWithoutAuditOrAttempt() {
        var f = scenario(false);
        assertTrue(rpcResult("peek_retry_inventory_sync", Map.of("commandId", f.command().id(),
            "idempotencyKey", "no-exception")).path("isError").asBoolean());
        assertEquals(1, commands.get(f.command().id()).attempts().size());
        assertTrue(audit.findByCommandIdOrderByStartedAtDesc(f.command().id()).isEmpty());
        var exit = events.ingest(new EventInput("physical", "EXIT-" + UUID.randomUUID(), "PHYSICAL_EXIT",
            NOW.minusSeconds(1), f.productId(), f.sku(), null, "FISCAL-ORDER", null, null, "MOVEMENT",
            BigDecimal.ONE, null, null, Map.of())).event();
        var fiscal = commands.create(CommandKind.FISCAL,
            new CommandService.CommandInput(exit.id(), f.channel(), "fiscal-" + exit.id(), true)).command();
        evaluator.evaluate(fiscal.deadlineAt().plusSeconds(1));
        var e03 = exceptions.forProduct(f.productId()).stream().filter(e -> e.code() == ExceptionCode.E03).findFirst().orElseThrow();
        assertEquals("E03", call("peek_get_exception", Map.of("exceptionId", e03.id())).path("exception").path("code").asText());
        assertFalse(call("peek_get_exception", Map.of("exceptionId", e03.id())).path("retryAllowed").asBoolean());
        assertTrue(rpcResult("peek_retry_inventory_sync", Map.of("commandId", fiscal.id(),
            "idempotencyKey", "forbidden-fiscal")).path("isError").asBoolean());
        assertEquals(1, commands.get(fiscal.id()).attempts().size());
    }

    @Test void listingSupportsOpenDefaultCodeAndPagination() {
        var f = scenario(true);
        var page = call("peek_list_exceptions", Map.of("code", "E01", "limit", 1, "offset", 0));
        assertEquals(1, page.path("exceptions").size());
        assertEquals("E01", page.path("exceptions").get(0).path("code").asText());
        assertEquals("OPEN", page.path("exceptions").get(0).path("status").asText());
        assertTrue(page.path("total").asInt() >= 1);
        assertTrue(rpcResult("peek_list_exceptions", Map.of("limit", 101)).path("isError").asBoolean());
    }

    @Test void transportRequiresBearerTokenAndRejectsBrowserOrigins() {
        assertEquals(HttpStatus.UNAUTHORIZED, http.postForEntity("/mcp", Map.of(), String.class).getStatusCode());
        var headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        headers.set("Origin", "https://evil.example");
        assertEquals(HttpStatus.FORBIDDEN,
            http.postForEntity("/mcp", new HttpEntity<>(Map.of(), headers), String.class).getStatusCode());
    }
}
