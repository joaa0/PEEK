package io.peek.core.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class HttpLlmClientTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private int status = 200;
    private long delay;
    private boolean delayAfterHeaders;
    private String body;

    @BeforeEach void start() throws Exception {
        body = JevFixtures.typeSafeResponse();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/v1/systemone", exchange -> {
            calls.incrementAndGet();
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try { if (delay > 0 && !delayAfterHeaders) Thread.sleep(delay); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "/v1/systemone");
                exchange.sendResponseHeaders(status, bytes.length);
                if (delayAfterHeaders) {
                    try { Thread.sleep(delay); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    private HttpLlmClient client() {
        var props = new LlmProperties(true, true, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone",
            "jev-latest", "synthetic-test-token", Duration.ofMillis(300), Duration.ofMillis(100));
        return new HttpLlmClient(props, json, new JevResponseValidator(json));
    }
    private JevContract.PreparedContext context() { return new JevInputFactory().prepare(JevFixtures.alert(), JevFixtures.events()); }
    private JevContract.Input input() { return context().input(); }
    private ObjectNode response() throws Exception { return (ObjectNode) json.readTree(JevFixtures.typeSafeResponse()); }
    private ObjectNode answer(ObjectNode value) { return (ObjectNode) value.path("answers").path("hypothesis"); }
    private ObjectNode distribution(ObjectNode value) { return (ObjectNode) answer(value).path("probabilities"); }
    private void rejected(String value) {
        body = value;
        assertEquals(LlmClient.FailureReason.INVALID_RESPONSE,
            assertThrows(LlmClient.Failure.class, () -> client().interpret(input())).reason());
    }

    @Test void sendsTypeSafeChoiceWithSanitizedStateAndPreservesProviderProvenance() throws Exception {
        var context = context();
        var output = new JevResponseValidator(json).validate(client().interpret(context.input()), context);
        var sent = json.readTree(request.get());
        assertEquals("jev-latest", sent.path("model").asText()); assertEquals(3, sent.size());
        assertFalse(sent.has("messages")); assertFalse(sent.has("response_format"));
        assertEquals("choice", sent.path("questions").path("hypothesis").path("type").asText());
        var criteria = sent.path("questions").path("hypothesis").path("criteria");
        assertEquals(3, criteria.size()); assertFalse(criteria.has("RECEIPT_RECORDING_GAP"));
        assertFalse(criteria.has("ADJUSTMENT_REQUIRES_REVIEW"));
        assertEquals("Bearer synthetic-test-token", authorization.get());
        String state = sent.path("state").toString();
        for (String forbidden : List.of("metadata", "MUST_NOT_LEAVE_PEEK", JevFixtures.SKU,
                JevFixtures.COUNT.toString(), "synthetic-test-token")) assertFalse(state.contains(forbidden));
        assertEquals(95, sent.path("state").path("expectedState").path("expectedStock").asInt());
        assertEquals("TYPESAFE", output.evaluation().provider()); assertEquals("jev-test", output.evaluation().model());
        assertEquals(new java.math.BigDecimal("0.4"), output.evaluation().confidence());
        assertEquals(new java.math.BigDecimal("0.6"), output.mainHypothesis().confidence().value());
        assertEquals(JevContract.TYPESAFE_PROBABILITY, output.mainHypothesis().confidence().meaning());
        assertEquals("PEEK_EVIDENCE_TEMPLATES", output.evaluation().explanationSource());
        assertTrue(output.mainHypothesis().rationale().contains("Texto explicativo composto pelo PEEK"));
        assertEquals(List.of("e1", "e3"), output.mainHypothesis().evidenceIds());
    }
    @Test void unexplainedChoiceDoesNotInventACauseOrAuthorizeAnAction() throws Exception {
        var value = response(); answer(value).put("choice", "UNEXPLAINED_DIVERGENCE");
        distribution(value).put("UNEXPLAINED_DIVERGENCE", 0.6).put("COUNT_REQUIRES_VERIFICATION", 0.3);
        body = value.toString(); var context = context();
        var output = new JevResponseValidator(json).validate(client().interpret(context.input()), context);
        assertEquals(JevContract.HypothesisCode.UNEXPLAINED_DIVERGENCE, output.mainHypothesis().code());
        assertTrue(output.recommendedAction().contains("não autoriza")); assertEquals(JevContract.NATURE, output.nature());
        assertEquals(2, output.alternatives().size());
        assertTrue(output.alternatives().stream().noneMatch(h -> h.code() == JevContract.HypothesisCode.UNEXPLAINED_DIVERGENCE));
    }
    @Test void receiptAndAdjustmentRequireOwnEvidenceAndAlternativesStayRanked() throws Exception {
        var original = input(); var facts = new java.util.ArrayList<>(original.facts());
        facts.add(new JevContract.Fact("e7", "MOVEMENT", io.peek.core.events.EventType.GOODS_RECEIVED, "source3", 5, java.math.BigDecimal.TEN, true));
        facts.add(new JevContract.Fact("e8", "MOVEMENT", io.peek.core.events.EventType.STOCK_ADJUSTED, "source3", 3, java.math.BigDecimal.ONE, true));
        var input = new JevContract.Input(original.contractVersion(), original.exceptionCode(), original.ruleTriggered(),
            original.dataClassification(), original.expectedState(), original.observedState(), facts, original.calculations(),
            original.configuration(), original.recentEvents(), true);
        var value = response(); answer(value).put("choice", "RECEIPT_RECORDING_GAP");
        distribution(value).put("COUNT_REQUIRES_VERIFICATION", 0.2).put("MOVEMENT_RECORDING_GAP", 0.1)
            .put("UNEXPLAINED_DIVERGENCE", 0.05).put("RECEIPT_RECORDING_GAP", 0.4).put("ADJUSTMENT_REQUIRES_REVIEW", 0.25);
        body = value.toString(); var output = json.readTree(client().interpret(input));
        assertEquals(5, json.readTree(request.get()).path("questions").path("hypothesis").path("criteria").size());
        assertEquals("RECEIPT_RECORDING_GAP", output.path("mainHypothesis").path("code").asText());
        assertTrue(output.path("mainHypothesis").path("evidenceIds").toString().contains("e7"));
        assertEquals(2, output.path("alternatives").size());
        assertEquals("ADJUSTMENT_REQUIRES_REVIEW", output.path("alternatives").get(0).path("code").asText());
        assertEquals("COUNT_REQUIRES_VERIFICATION", output.path("alternatives").get(1).path("code").asText());
        assertTrue(output.path("mainHypothesis").path("rationale").asText().contains("histórico enviado está limitado"));
    }
    @Test void malformedDuplicateAndTrailingJsonAreRejected() {
        for (String value : List.of("not JSON", "{}", "null", JevFixtures.typeSafeResponse() + " {}",
                JevFixtures.typeSafeResponse().replace("\"model\":", "\"model\":\"jev-duplicate\",\"model\":"))) rejected(value);
    }
    @Test void incompatibleAnswerMissingUsageOrUnrelatedModelCannotBePublished() throws Exception {
        var value = response(); answer(value).put("type", "score"); rejected(value.toString());
        value = response(); answer(value).remove("confidence"); rejected(value.toString());
        value = response(); answer(value).put("confidence", "0.4"); rejected(value.toString());
        value = response(); answer(value).put("confidence", 1.1); rejected(value.toString());
        value = response(); value.put("model", "gpt-4.1-mini"); rejected(value.toString());
        value = response(); value.remove("usage"); rejected(value.toString());
        value = response(); ((ObjectNode) value.path("answers")).putObject("another_question"); rejected(value.toString());
    }
    @Test void probabilitiesMustCoverExactlyProposedOptionsAndFormADistribution() throws Exception {
        var value = response(); distribution(value).put("RECEIPT_RECORDING_GAP", 0); rejected(value.toString());
        value = response(); distribution(value).remove("MOVEMENT_RECORDING_GAP"); rejected(value.toString());
        value = response(); distribution(value).put("COUNT_REQUIRES_VERIFICATION", -0.1); rejected(value.toString());
        value = response(); distribution(value).put("COUNT_REQUIRES_VERIFICATION", 1.1); rejected(value.toString());
        value = response(); distribution(value).put("COUNT_REQUIRES_VERIFICATION", "0.6"); rejected(value.toString());
        value = response(); distribution(value).put("COUNT_REQUIRES_VERIFICATION", 0.7); rejected(value.toString());
    }
    @Test void unknownOrLowerProbabilityChoiceIsRejectedRatherThanRewritten() throws Exception {
        var value = response(); answer(value).put("choice", "RECEIPT_RECORDING_GAP"); rejected(value.toString());
        value = response(); answer(value).put("choice", "MOVEMENT_RECORDING_GAP"); rejected(value.toString());
    }
    @Test void normalFloatingPointRoundingIsAccepted() throws Exception {
        var value = response(); distribution(value).put("COUNT_REQUIRES_VERIFICATION", 0.60000001);
        body = value.toString(); assertNotNull(client().interpret(input()));
    }
    @Test void localSchemaRejectsTamperedProvenanceAndConfidenceWithoutDistribution() throws Exception {
        var context = context(); var returned = (ObjectNode) json.readTree(client().interpret(context.input()));
        var validator = new JevResponseValidator(json);
        var tampered = returned.deepCopy(); tampered.remove("evaluation");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(tampered.toString(), context));
        var wrongProvider = returned.deepCopy(); ((ObjectNode) wrongProvider.path("evaluation")).put("provider", "OPENAI");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(wrongProvider.toString(), context));
        var wrongProbability = returned.deepCopy(); ((ObjectNode) wrongProbability.path("mainHypothesis").path("confidence")).put("value", 0.7);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(wrongProbability.toString(), context));
    }
    @Test void apiFailureAndRedirectCannotTriggerAnotherRequestOrLeakBody() {
        for (int code : new int[] {400, 401, 403, 429, 500, 529, 302}) {
            status = code; body = "provider-private-message"; int before = calls.get();
            var failure = assertThrows(LlmClient.Failure.class, () -> client().interpret(input()));
            assertEquals(LlmClient.FailureReason.API_FAILURE, failure.reason());
            assertFalse(failure.getMessage().contains(body)); assertEquals(before + 1, calls.get());
        }
    }
    @Test void timeoutIsBoundedAndReturnsAClassifiedFailure() {
        delay = 1500; long start = System.nanoTime();
        assertEquals(LlmClient.FailureReason.TIMEOUT, assertThrows(LlmClient.Failure.class, () -> client().interpret(input())).reason());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1200);
    }
    @Test void oversizedResponseIsCancelledAndRejected() {
        body = " ".repeat(40_000);
        assertEquals(LlmClient.FailureReason.INVALID_RESPONSE, assertThrows(LlmClient.Failure.class, () -> client().interpret(input())).reason());
    }
    @Test void stalledResponseBodyAlsoRespectsTheOverallTimeout() {
        delay = 1500; delayAfterHeaders = true; long start = System.nanoTime();
        assertEquals(LlmClient.FailureReason.TIMEOUT, assertThrows(LlmClient.Failure.class, () -> client().interpret(input())).reason());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1200);
    }
    @Test void configurationKeepsTheTypeSafeKeyAwayFromOtherProvidersAndUnsafeEndpoints() {
        var configured = new LlmProperties(true, true, "https://api.typesafe.ai/v1/systemone", "jev-latest", "synthetic-test-token",
            Duration.ofSeconds(3), Duration.ofSeconds(1));
        assertTrue(configured.configured()); assertFalse(configured.toString().contains("synthetic-test-token"));
        for (String endpoint : List.of("", "http://api.typesafe.ai/v1/systemone", "https://token@api.typesafe.ai/v1/systemone",
                "https://api.typesafe.ai/v1/systemone?key=x", "https://api.typesafe.ai/v1/systemone#x",
                "https://api.typesafe.ai:444/v1/systemone", "https://api.typesafe.ai/wrong", "https://api.openai.com/v1/chat/completions"))
            assertFalse(new LlmProperties(true, true, endpoint, "jev-latest", "synthetic-test-token", Duration.ofSeconds(3), Duration.ofSeconds(1)).configured());
        assertEquals(0, calls.get());
    }
}
