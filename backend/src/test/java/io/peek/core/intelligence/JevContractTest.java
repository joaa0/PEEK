package io.peek.core.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.peek.core.events.EventType;
import io.peek.core.events.NormalizedEvent;
import io.peek.core.exceptions.ExceptionService;
import io.peek.core.operational_state.StockCalculator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JevContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final JevResponseValidator validator = new JevResponseValidator(json);
    private final JevInputFactory factory = new JevInputFactory();
    private JevContract.PreparedContext context() { return factory.prepare(JevFixtures.alert(), JevFixtures.events()); }

    @Test void inputSeparatesFactsCalculationsStatesAndOnlyAllowlistedSyntheticFields() throws Exception {
        var context = context(); validator.validateInput(context.input());
        assertEquals(3, context.input().facts().size()); assertEquals(2, context.input().calculations().size());
        assertEquals(95, context.input().expectedState().expectedStock().intValue());
        assertEquals(93, context.input().observedState().physicalStock().intValue());
        var serialized = json.writeValueAsString(context.input());
        for (String forbidden : new String[] {"MUST_NOT_LEAVE_PEEK", "metadata", "external", "fictitious-source", JevFixtures.SKU,
                JevFixtures.PRODUCT.toString(), JevFixtures.COUNT.toString(), "2026-01-01"})
            assertFalse(serialized.contains(forbidden), forbidden);
        assertThrows(UnsupportedOperationException.class, () -> context.input().facts().clear());
    }
    @Test void structuredOutputHasGroundedMainHypothesisAndExplicitModelConfidence() {
        var result = validator.validate(JevFixtures.output(), context());
        assertEquals(JevContract.NATURE, result.nature()); assertEquals(JevContract.CONFIDENCE_MEANING, result.mainHypothesis().confidence().meaning());
        assertFalse(result.mainHypothesis().rationale().isBlank()); assertTrue(result.alternatives().isEmpty());
    }
    @Test void historyUsesTheSameReceivedAtAndIdTieBreakersAsDeterministicStock() throws Exception {
        UUID receiptId = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        for (boolean sameReceivedAt : List.of(false, true)) {
            var original = JevFixtures.events();
            var sale = tiedMovement(JevFixtures.SALE, EventType.SALE_CONFIRMED, 14, "5");
            var receipt = tiedMovement(receiptId, EventType.GOODS_RECEIVED, sameReceivedAt ? 14 : 15, "7");
            var movements = List.of(sale, receipt, original.getFirst());
            var snapshot = new StockCalculator().calculate(JevFixtures.SKU, movements);
            assertEquals(new BigDecimal("102"), snapshot.expectedStock());
            assertEquals(sameReceivedAt ? List.of(JevFixtures.BASELINE, JevFixtures.SALE, receiptId)
                : List.of(JevFixtures.BASELINE, receiptId, JevFixtures.SALE), snapshot.usedEventIds());

            var alertJson = (ObjectNode) json.valueToTree(JevFixtures.alert());
            alertJson.put("expectedState", "102");
            var rows = alertJson.withArray("evidence");
            ((ObjectNode) rows.get(1)).put("value", "102");
            ((ObjectNode) rows.get(2)).put("value", "-9");
            rows.add(json.valueToTree(new ExceptionService.EvidenceView(
                UUID.fromString("00000000-0000-0000-0000-000000000007"), receiptId, "MOVEMENT",
                receipt.source(), "GOODS_RECEIVED", "7", receipt.occurredAt())));
            var alert = json.treeToValue(alertJson, ExceptionService.ExceptionView.class);
            var events = new ArrayList<>(movements); events.addFirst(original.getLast());
            var input = factory.prepare(alert, events).input(); validator.validateInput(input);
            var engineTypes = snapshot.usedEventIds().stream()
                .map(id -> movements.stream().filter(event -> event.id().equals(id)).findFirst().orElseThrow().type())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            engineTypes.add(EventType.PHYSICAL_COUNT);
            assertEquals(engineTypes, input.recentEvents().stream().map(JevContract.RecentEvent::type).toList());
            assertEquals(alertJson, json.valueToTree(alert));
            assertFalse(json.writeValueAsString(input).contains("receivedAt"));
        }
    }
    private NormalizedEvent tiedMovement(UUID id, EventType type, int receivedSeconds, String quantity) {
        var sample = JevFixtures.events().get(1);
        return new NormalizedEvent(id, sample.source(), sample.externalEventId(), type, sample.occurredAt(),
            JevFixtures.NOW.minusSeconds(receivedSeconds), sample.productId(), sample.sku(), sample.externalProductId(),
            sample.orderId(), sample.invoiceId(), sample.receiptId(), sample.movementId(), new BigDecimal(quantity),
            null, false, sample.metadata());
    }
    @Test void inputSchemaRejectsWrongVersionRealDataNegativeCountAndUnboundedHistory() throws Exception {
        String valid = json.writeValueAsString(context().input());
        for (String invalid : new String[] {valid.replace("\"contractVersion\":\"1.0\"", "\"contractVersion\":\"2.0\""),
                valid.replace("SYNTHETIC_DEMO", "REAL_OPERATIONAL_DATA"),
                valid.replace("\"physicalStock\":93", "\"physicalStock\":-1"),
                valid.replace("\"tolerance\":1", "\"tolerance\":-1"), valid.replace("source1", "raw-source-name")}) {
            var input = json.readValue(invalid, JevContract.Input.class);
            assertThrows(IllegalArgumentException.class, () -> validator.validateInput(input));
        }
        var tooLong = (ObjectNode) json.valueToTree(context().input());
        var history = tooLong.withArray("recentEvents");
        while (history.size() <= JevInputFactory.MAX_HISTORY) history.add(history.get(0).deepCopy());
        var input = json.treeToValue(tooLong, JevContract.Input.class);
        assertThrows(IllegalArgumentException.class, () -> validator.validateInput(input));
    }
    @Test void invalidSchemaMissingFieldsTypesRangesUnknownFieldsAndVersionAreRejected() throws Exception {
        for (String invalid : new String[] {"{}", "null", "[]", "not JSON", JevFixtures.output().replace("0.6", "1.1"),
                JevFixtures.output().replace("0.6", "\"0.6\""), JevFixtures.output().replace("MODEL_SELF_REPORTED_RANKING", "STATISTICAL_PROBABILITY"),
                JevFixtures.output().replace("1.0", "2.0"), JevFixtures.output().replace("\"alternatives\":[]", "\"facts\":[],\"alternatives\":[]"),
                JevFixtures.output() + "{}", JevFixtures.output().replace("\"alternatives\":[]", "\"alternatives\":null"),
                JevFixtures.output().replace("\"impact\":", "\"summary\":\"duplicate\",\"impact\":"), JevFixtures.output().replace("A contagem pode estar incompleta.", "  ")})
            assertThrows(IllegalArgumentException.class, () -> validator.validate(invalid, context()), invalid);
    }
    @Test void unknownReferencesAndContextuallyUnsupportedCausesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(JevFixtures.output().replace("e1", "e99"), context()));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(JevFixtures.output().replace("COUNT_REQUIRES_VERIFICATION", "RECEIPT_RECORDING_GAP"), context()));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(JevFixtures.output().replace("[\"e1\",\"e3\"]", "[\"e3\"]"), context()));
    }
    @Test void alternativesRequireRelevantEvidenceDistinctCodesAndDescendingRanking() throws Exception {
        var node = (ObjectNode) json.readTree(JevFixtures.output());
        var alternative = node.path("mainHypothesis").deepCopy();
        ((ObjectNode) alternative).put("code", "MOVEMENT_RECORDING_GAP");
        ((ObjectNode) alternative).putArray("evidenceIds").add("e6");
        ((ObjectNode) alternative.path("confidence")).put("value", 0.4);
        node.withArray("alternatives").add(alternative);
        assertEquals(1, validator.validate(node.toString(), context()).alternatives().size());
        ((ObjectNode) alternative.path("confidence")).put("value", 0.9);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(node.toString(), context()));
        ((ObjectNode) alternative.path("confidence")).put("value", 0.4);
        node.withArray("alternatives").add(alternative.deepCopy());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(node.toString(), context()));
    }
    @Test void changedOrIncompletePersistedEvidenceCannotBeSentAsModelFacts() {
        assertThrows(IllegalArgumentException.class, () -> factory.prepare(JevFixtures.alert(), java.util.List.of()));
        var events = new ArrayList<>(JevFixtures.events()); events.removeFirst();
        assertThrows(IllegalArgumentException.class, () -> factory.prepare(JevFixtures.alert(), events));
        assertThrows(IllegalArgumentException.class, () -> factory.prepare(JevFixtures.alert(), java.util.List.of(JevFixtures.events().getFirst())));
    }
}
