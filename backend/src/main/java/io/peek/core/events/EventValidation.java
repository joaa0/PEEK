package io.peek.core.events;

import java.math.BigDecimal;
import java.util.Objects;

public final class EventValidation {
    private EventValidation() {}

    public static EventType validate(EventInput input) {
        Objects.requireNonNull(input, "event");
        required(input.source(), "source");
        required(input.externalEventId(), "externalEventId");
        if (input.occurredAt() == null) throw new IllegalArgumentException("occurredAt is required");
        String typeName = required(input.type(), "type");
        EventType type;
        try {
            type = EventType.valueOf(typeName);
        } catch (IllegalArgumentException error) {
            throw new UnsupportedEventTypeException(input.type());
        }
        if (input.productId() == null && blank(input.sku()) && blank(input.externalProductId()) && blank(input.invoiceId())) {
            throw new IllegalArgumentException("productId, sku, externalProductId or invoiceId is required");
        }
        if (type != EventType.INVOICE_ISSUED && input.productId() == null && blank(input.sku()) && blank(input.externalProductId())) {
            throw new IllegalArgumentException("product identity is required for this event type");
        }
        switch (type) {
            case SALE_CONFIRMED -> { positive(input.quantity()); required(input.orderId(), "orderId"); }
            case GOODS_RECEIVED -> { positive(input.quantity()); required(input.receiptId(), "receiptId"); }
            case PHYSICAL_EXIT -> positive(input.quantity());
            case PHYSICAL_COUNT -> { nonnegative(input.quantity()); if (input.confirmed() == null) throw new IllegalArgumentException("confirmed is required for PHYSICAL_COUNT"); }
            case STOCK_ADJUSTED -> { if (input.quantity() == null || input.quantity().signum() == 0) throw new IllegalArgumentException("non-zero quantity delta is required"); }
            case STOCK_UPDATED -> { if (input.quantity() == null && input.stockAfter() == null) throw new IllegalArgumentException("quantity or stockAfter is required"); if (input.stockAfter() != null) nonnegative(input.stockAfter()); }
            case INVOICE_ISSUED -> required(input.invoiceId(), "invoiceId");
        }
        if (input.metadata() != null && (input.metadata().size() > 32 || input.metadata().entrySet().stream().anyMatch(e -> blank(e.getKey()) || e.getValue() == null))) {
            throw new IllegalArgumentException("metadata must have at most 32 non-null entries with nonblank keys");
        }
        return type;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String required(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static void positive(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("positive quantity is required");
    }
    private static void nonnegative(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("nonnegative quantity is required");
    }
}
