package io.peek.core.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.peek.core.events.EventType;
import io.peek.core.events.NormalizedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrelationPolicyTest {
    private final CorrelationPolicy policy = new CorrelationPolicy();
    private NormalizedEvent event(String sku, String order, String receipt, String movement) {
        return new NormalizedEvent(UUID.randomUUID(), "mock", "id", EventType.SALE_CONFIRMED,
            Instant.EPOCH, Instant.EPOCH, null, sku, null, order, null, receipt, movement,
            BigDecimal.ONE, null, false, Map.of());
    }
    @Test void orderAndSkuMustBothMatch() {
        var trigger = event("A", "O1", null, null);
        assertEquals(CorrelationPolicy.Match.MATCH, policy.compare(trigger, event("A", "O1", null, null), CorrelationPolicy.Key.ORDER_ID_AND_SKU));
        assertEquals(CorrelationPolicy.Match.DIFFERENT, policy.compare(trigger, event("B", "O1", null, null), CorrelationPolicy.Key.ORDER_ID_AND_SKU));
        assertEquals(CorrelationPolicy.Match.DIFFERENT, policy.compare(trigger, event("A", "O2", null, null), CorrelationPolicy.Key.ORDER_ID_AND_SKU));
        assertEquals(CorrelationPolicy.Match.MISSING_KEY, policy.compare(trigger, event("A", null, null, null), CorrelationPolicy.Key.ORDER_ID_AND_SKU));
    }
    @Test void receiptAndMovementStrategiesRemainSeparate() {
        var trigger = event("A", "O1", "R1", "M1");
        var other = event("A", "O2", "R1", "M2");
        assertEquals(CorrelationPolicy.Match.MATCH, policy.compare(trigger, other, CorrelationPolicy.Key.RECEIPT_ID_AND_SKU));
        assertEquals(CorrelationPolicy.Match.DIFFERENT, policy.compare(trigger, other, CorrelationPolicy.Key.MOVEMENT_ID_AND_SKU));
        assertEquals("movementId", policy.missingTriggerField(event("A", "O1", "R1", null), CorrelationPolicy.Key.MOVEMENT_ID_AND_SKU));
    }
}
