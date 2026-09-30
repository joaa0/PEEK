package io.peek.core;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.peek.core.events.EventService;
import io.peek.core.exceptions.*;
import io.peek.core.intelligence.JevFixtures;
import io.peek.core.integrations.*;
import io.peek.core.products.MappingStatus;
import io.peek.core.products.ProductService;
import io.peek.core.reconciliation.EvaluationService;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"peek.llm.enabled=true", "peek.llm.synthetic-demo=true", "peek.llm.model=test-model",
        "peek.llm.api-key=", "peek.llm.timeout=300ms", "peek.llm.connect-timeout=100ms",
        "peek.demo.clock=2026-01-01T12:00:00Z", "peek.mcp.enabled=true",
        "peek.mcp.token=test-only-token-with-at-least-32-characters"})
@ActiveProfiles("demo")
class JevIntegrationIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> REQUEST = new AtomicReference<>();
    private static final java.util.concurrent.ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer SERVER = server();
    private static volatile int status = 200;
    private static volatile long delay;
    private static volatile String output = JevFixtures.typeSafeResponse();
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(POOL);
            server.createContext("/v1/systemone", exchange -> {
                CALLS.incrementAndGet(); REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int code = status; String content = output; long wait = delay;
                try { if (wait > 0) Thread.sleep(wait); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                try {
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("PEEK_TEST_DB_URL", "jdbc:postgresql://localhost:5432/peek_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PEEK_TEST_DB_USER", "peek"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("PEEK_TEST_DB_PASSWORD", "peek_local_only"));
        registry.add("peek.llm.endpoint", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1/systemone");
    }
    @AfterAll static void stop() { SERVER.stop(0); POOL.shutdownNow(); }
    @BeforeEach void reset() { CALLS.set(0); REQUEST.set(null); status = 200; delay = 0; output = JevFixtures.typeSafeResponse(); }
    @Autowired TestRestTemplate http;
    @Autowired ProductService products;
    @Autowired EventService events;
    @Autowired ExceptionService exceptions;
    @Autowired EvaluationService evaluator;
    @Autowired MockInventoryAdapter inventory;
    @Autowired MockSalesAdapter sales;
    @Autowired MockPhysicalAdapter physical;

    private ExceptionService.ExceptionView scenario() {
        String run = "DEMO-JEV-" + UUID.randomUUID(); String sku = "SKU-E02-" + run;
        var product = products.create(new ProductService.ProductInput(sku, "Fictitious demo", null, null, null, "Demo")).value();
        String stockItem = "STOCK-" + run, saleItem = "SALE-" + run;
        products.addMapping(product.id(), new ProductService.MappingInput("demo-inventory", stockItem, MappingStatus.ACTIVE));
        products.addMapping(product.id(), new ProductService.MappingInput("demo-sales", saleItem, MappingStatus.ACTIVE));
        events.ingest(inventory.translate(new MockInventoryAdapter.StockNotice("base-" + run, "demo-inventory", stockItem,
            null, null, null, new BigDecimal("100"), JevFixtures.NOW.minusSeconds(30))));
        events.ingest(sales.translate(new MockSalesAdapter.SaleNotice("sale-" + run, "demo-sales", "ORDER-" + run, saleItem,
            new BigDecimal("5"), JevFixtures.NOW.minusSeconds(20))));
        events.ingest(physical.translate(new MockPhysicalAdapter.PhysicalNotice("count-" + run, "demo-physical", "COUNT", sku,
            null, null, null, new BigDecimal("93"), true, JevFixtures.NOW.minusSeconds(10))));
        evaluator.evaluate(JevFixtures.NOW);
        return exceptions.forProduct(product.id()).stream().filter(e -> e.code() == ExceptionCode.E02).findFirst().orElseThrow();
    }
    private JsonNode context(ExceptionService.ExceptionView alert) {
        var response = http.getForEntity("/api/v1/exceptions/" + alert.id() + "/context", JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode()); return response.getBody();
    }
    private void preserved(ExceptionService.ExceptionView original) {
        var persisted = exceptions.get(original.id()); assertEquals(original.evidence(), persisted.evidence());
        assertEquals(ExceptionStatus.OPEN, persisted.status()); assertNull(persisted.reconciliationEventId());
        assertEquals(3, events.forSku(original.sku()).size());
    }
    private JsonNode tool(String name, Map<String, Object> arguments) {
        var headers = new HttpHeaders();
        headers.setBearerAuth("test-only-token-with-at-least-32-characters");
        headers.set("Accept", "application/json, text/event-stream");
        var response = http.postForEntity("/mcp", new HttpEntity<>(Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(),
            "method", "tools/call", "params", Map.of("name", name, "arguments", arguments)), headers), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().has("error"), response.getBody().toString());
        assertFalse(response.getBody().path("result").path("isError").asBoolean(), response.getBody().toString());
        return response.getBody().path("result").path("structuredContent");
    }
    @Test void mcpCarriesRealJevProjectionWithoutApprovingOrResolvingTheException() {
        var alert = scenario();
        var value = tool("peek_get_operational_context", Map.of("exceptionId", alert.id()));
        assertEquals(95, value.path("factsAndEvidence").path("expectedStock").asInt());
        assertEquals(93, value.path("factsAndEvidence").path("physicalStock").asInt());
        assertTrue(value.path("factsAndEvidence").path("humanApprovalRequired").asBoolean());
        var jev = value.path("jevInterpretation");
        assertEquals("AVAILABLE", jev.path("status").asText());
        assertEquals("HYPOTHESIS_NOT_FACT", jev.path("nature").asText());
        assertTrue(jev.path("confidence").asText().contains("TYPESAFE_CHOICE_PROBABILITY"));
        assertEquals(alert.evidence().getFirst().id().toString(), jev.path("supportingEvidence").get(0).asText());
        assertEquals(1, CALLS.get());
        assertEquals(0, tool("peek_get_exception_status", Map.of("exceptionId", alert.id())).path("agentActions").size());
        preserved(alert);
    }
    @Test void jevAndApprovedMcpActionRequireSeparateDeterministicVerificationAndPreserveHistory() {
        var alert = scenario();
        var reviewed = tool("peek_get_operational_context", Map.of("exceptionId", alert.id()));
        assertEquals("AVAILABLE", reviewed.path("jevInterpretation").path("status").asText());
        var input = Map.<String, Object>of("exceptionId", alert.id(), "idempotencyKey", "synthetic-test-" + alert.id(),
            "humanApproved", true, "approvalNote", "Explicit approval simulated only in this automated fictitious test",
            "decisionFingerprint", reviewed.path("factsAndEvidence").path("decisionFingerprint").asText());
        var accepted = tool("peek_apply_e02_reconciliation", input);
        assertEquals("PENDING_VERIFICATION", accepted.path("action").path("verificationStatus").asText());
        preserved(alert);
        evaluator.evaluate(JevFixtures.NOW);
        var finalState = tool("peek_get_exception_status", Map.of("exceptionId", alert.id()));
        assertEquals("RESOLVED", finalState.path("status").asText());
        assertEquals("VERIFIED", finalState.path("agentActions").get(0).path("verificationStatus").asText());
        assertEquals(alert.triggerEventId().toString(), finalState.path("reconciliationEventId").asText());
        assertFalse(finalState.path("reconciledAt").isNull());
        assertTrue(exceptions.get(alert.id()).evidence().containsAll(alert.evidence()));
        assertEquals(3, events.forSku(alert.sku()).size());
        assertTrue(tool("peek_apply_e02_reconciliation", input).path("replayed").asBoolean());
        assertEquals(1, tool("peek_get_exception_status", Map.of("exceptionId", alert.id())).path("agentActions").size());
        assertEquals(1, CALLS.get(), "Actions and verification must not invoke the LLM");
    }
    @Test void mcpKeepsFactsAndApprovalBoundaryWhenJevIsUnavailable() {
        status = 503; var alert = scenario();
        var value = tool("peek_get_operational_context", Map.of("exceptionId", alert.id()));
        assertEquals("UNAVAILABLE", value.path("jevInterpretation").path("status").asText());
        assertTrue(value.path("jevInterpretation").path("confidence").isNull());
        assertEquals(95, value.path("factsAndEvidence").path("expectedStock").asInt());
        assertEquals(0, tool("peek_get_exception_status", Map.of("exceptionId", alert.id())).path("agentActions").size());
        preserved(alert);
    }
    @Test void deterministicDetectionListAndDetailDoNotDependOnAnyLlmCall() {
        status = 503; var alert = scenario();
        assertEquals("95", alert.expectedState()); assertEquals("93", alert.observedState()); assertEquals(0, CALLS.get());
        assertEquals(HttpStatus.OK, http.getForEntity("/api/v1/exceptions/" + alert.id(), JsonNode.class).getStatusCode());
        assertEquals(HttpStatus.OK, http.getForEntity("/api/v1/exceptions?code=E02", JsonNode.class).getStatusCode());
        assertEquals(0, CALLS.get()); preserved(alert);
    }
    @Test void realApiReturnsFrontendReadyAnalysisAndKeepsOriginalEvidence() throws Exception {
        var alert = scenario(); var response = context(alert);
        assertEquals("AVAILABLE", response.path("jev").path("status").asText());
        assertEquals(95, response.path("stockAtDetection").path("expectedStock").asInt());
        assertEquals(93, response.path("currentStock").path("expectedStock").asInt());
        var support = response.path("jev").path("mainHypothesis").path("evidenceIds");
        assertEquals(alert.evidence().getFirst().id().toString(), support.get(0).asText());
        assertEquals("TYPESAFE", response.path("jev").path("evaluation").path("provider").asText());
        assertEquals("jev-test", response.path("jev").path("evaluation").path("model").asText());
        String sent = JSON.readTree(REQUEST.get()).path("state").toString();
        assertFalse(sent.contains(alert.sku())); assertFalse(sent.contains(alert.id().toString())); assertFalse(sent.contains("ORDER-"));
        assertFalse(sent.contains("metadata")); preserved(alert);
    }
    @Test void invalidJsonUsesDeterministicFallbackAndKeepsE02Visible() {
        output = "not JSON"; var alert = scenario(); var response = context(alert);
        assertEquals("FALLBACK", response.path("jev").path("status").asText());
        assertEquals("INVALID_RESPONSE", response.path("jev").path("fallbackReason").asText());
        assertEquals(alert.recommendation(), response.path("jev").path("recommendedAction").asText());
        assertTrue(response.path("jev").path("mainHypothesis").isNull()); preserved(alert);
    }
    @Test void hypothesisWithoutContextualEvidenceCannotBePublishedOrModifySourceEvents() {
        output = JevFixtures.typeSafeResponse().replace("\"choice\":\"COUNT_REQUIRES_VERIFICATION\"", "\"choice\":\"RECEIPT_RECORDING_GAP\"");
        var alert = scenario();
        assertEquals("INVALID_RESPONSE", context(alert).path("jev").path("fallbackReason").asText()); preserved(alert);
    }
    @Test void apiFailureUsesStaticExplanationAndNeverSuppressesE02() {
        status = 503; var alert = scenario();
        assertEquals("API_FAILURE", context(alert).path("jev").path("fallbackReason").asText()); preserved(alert);
    }
    @Test void timeoutUsesStaticExplanationAndNeverSuppressesE02() {
        delay = 1500; var alert = scenario();
        assertEquals("TIMEOUT", context(alert).path("jev").path("fallbackReason").asText()); preserved(alert);
    }
    @Test void laterMovementsNeverReplaceTheModelDetectionSnapshot() throws Exception {
        var alert = scenario();
        events.ingest(physical.translate(new MockPhysicalAdapter.PhysicalNotice("adjust-" + UUID.randomUUID(), "demo-physical", "ADJUSTMENT",
            alert.sku(), null, null, null, BigDecimal.ONE, null, JevFixtures.NOW.plusSeconds(1))));
        var response = context(alert); assertEquals("AVAILABLE", response.path("jev").path("status").asText());
        var input = JSON.readTree(REQUEST.get()).path("state");
        assertEquals(95, input.path("expectedState").path("expectedStock").asInt());
        assertEquals(93, input.path("observedState").path("physicalStock").asInt());
        assertEquals(3, input.path("recentEvents").size()); assertEquals(alert.evidence(), exceptions.get(alert.id()).evidence());
    }
}
