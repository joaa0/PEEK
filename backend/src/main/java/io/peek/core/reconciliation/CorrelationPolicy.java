package io.peek.core.reconciliation;

import io.peek.core.events.NormalizedEvent;

public final class CorrelationPolicy {
    public enum Key { ORDER_ID_AND_SKU, RECEIPT_ID_AND_SKU, MOVEMENT_ID_AND_SKU }
    public enum Match { MATCH, DIFFERENT, MISSING_KEY }

    public Match compare(NormalizedEvent trigger, NormalizedEvent candidate, Key key) {
        if (trigger == null || candidate == null || key == null) throw new IllegalArgumentException("Correlation inputs are required");
        if (blank(trigger.sku()) || blank(candidate.sku())) return Match.MISSING_KEY;
        String left = reference(trigger, key);
        String right = reference(candidate, key);
        if (blank(left) || blank(right)) return Match.MISSING_KEY;
        return trigger.sku().equals(candidate.sku()) && left.equals(right) ? Match.MATCH : Match.DIFFERENT;
    }

    public String missingTriggerField(NormalizedEvent trigger, Key key) {
        if (blank(trigger.sku())) return "sku";
        if (blank(reference(trigger, key))) return switch (key) {
            case ORDER_ID_AND_SKU -> "orderId";
            case RECEIPT_ID_AND_SKU -> "receiptId";
            case MOVEMENT_ID_AND_SKU -> "movementId";
        };
        return null;
    }

    private String reference(NormalizedEvent event, Key key) {
        return switch (key) {
            case ORDER_ID_AND_SKU -> event.orderId();
            case RECEIPT_ID_AND_SKU -> event.receiptId();
            case MOVEMENT_ID_AND_SKU -> event.movementId();
        };
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
