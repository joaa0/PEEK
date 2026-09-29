package io.peek.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventValidationTest {
    private final Instant time = Instant.parse("2026-01-01T12:00:00Z");

    private EventInput input(String type, BigDecimal quantity, String order, String receipt, String invoice, Boolean confirmed) {
        return new EventInput("sales", "evt-1", type, time, null, "SKU-1", null, order,
            invoice, receipt, null, quantity, null, confirmed, Map.of());
    }

    @Test void acceptsEachCanonicalTypeWithRequiredFields() {
        assertEquals(EventType.SALE_CONFIRMED, EventValidation.validate(input("SALE_CONFIRMED", BigDecimal.ONE, "O1", null, null, null)));
        assertEquals(EventType.GOODS_RECEIVED, EventValidation.validate(input("GOODS_RECEIVED", BigDecimal.ONE, null, "R1", null, null)));
        assertEquals(EventType.PHYSICAL_EXIT, EventValidation.validate(input("PHYSICAL_EXIT", BigDecimal.ONE, null, null, null, null)));
        assertEquals(EventType.PHYSICAL_COUNT, EventValidation.validate(input("PHYSICAL_COUNT", BigDecimal.ZERO, null, null, null, true)));
        assertEquals(EventType.STOCK_ADJUSTED, EventValidation.validate(input("STOCK_ADJUSTED", BigDecimal.ONE.negate(), null, null, null, null)));
        assertEquals(EventType.STOCK_UPDATED, EventValidation.validate(input("STOCK_UPDATED", BigDecimal.ONE, null, null, null, null)));
        assertEquals(EventType.INVOICE_ISSUED, EventValidation.validate(input("INVOICE_ISSUED", null, null, null, "I1", null)));
    }

    @Test void distinguishesMalformedFromUnsupported() {
        assertThrows(IllegalArgumentException.class, () -> EventValidation.validate(input(null, BigDecimal.ONE, null, null, null, null)));
        assertThrows(UnsupportedEventTypeException.class, () -> EventValidation.validate(input("UNKNOWN", BigDecimal.ONE, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> EventValidation.validate(input("SALE_CONFIRMED", BigDecimal.ZERO, "O1", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> EventValidation.validate(input("PHYSICAL_COUNT", BigDecimal.ONE, null, null, null, null)));
    }
}
