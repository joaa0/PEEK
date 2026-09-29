package io.peek.core.operational_state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.peek.core.events.EventType;
import io.peek.core.events.NormalizedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockCalculatorTest {
    private final StockCalculator calculator = new StockCalculator();
    private final Instant origin = Instant.parse("2026-01-01T00:00:00Z");

    private NormalizedEvent event(int minute, EventType type, String quantity, String stockAfter, boolean confirmed) {
        UUID id = new UUID(0, minute + 1L);
        return new NormalizedEvent(id, "source", "e" + minute, type, origin.plusSeconds(minute * 60L),
            origin.plusSeconds(minute * 60L + 1), null, "A", null, null, null, null, null,
            quantity == null ? null : new BigDecimal(quantity), stockAfter == null ? null : new BigDecimal(stockAfter),
            confirmed, Map.of());
    }

    @Test void calculates103AndKeepsObservedStatesSeparate() {
        var baseline = event(0, EventType.STOCK_UPDATED, null, "100", false);
        var receipt = event(1, EventType.GOODS_RECEIVED, "20", null, false);
        var sale = event(2, EventType.SALE_CONFIRMED, "15", null, false);
        var adjustment = event(3, EventType.STOCK_ADJUSTED, "-2", null, false);
        var snapshot = calculator.calculate("A", List.of(adjustment, sale, baseline, receipt));
        assertEquals(0, snapshot.expectedStock().compareTo(new BigDecimal("103")));
        assertEquals(0, snapshot.systemStock().compareTo(new BigDecimal("100")));
        assertNull(snapshot.physicalStock());
        assertEquals(List.of(baseline.id(), receipt.id(), sale.id(), adjustment.id()), snapshot.usedEventIds());
        assertEquals(baseline.id(), snapshot.baselineEventId());
    }

    @Test void confirmedCountReanchorsFutureCalculationWithoutMutatingHistory() {
        var baseline = event(0, EventType.STOCK_UPDATED, null, "100", false);
        var sale = event(1, EventType.SALE_CONFIRMED, "5", null, false);
        var count = event(2, EventType.PHYSICAL_COUNT, "93", null, true);
        var laterSale = event(3, EventType.SALE_CONFIRMED, "2", null, false);
        List<NormalizedEvent> history = List.of(baseline, sale, count, laterSale);
        var snapshot = calculator.calculate("A", history);
        assertEquals(0, snapshot.expectedStock().compareTo(new BigDecimal("91")));
        assertEquals(0, snapshot.systemStock().compareTo(new BigDecimal("100")));
        assertEquals(0, snapshot.physicalStock().compareTo(new BigDecimal("93")));
        assertEquals(count.id(), snapshot.checkpointEventId());
        assertEquals(count.id(), snapshot.baselineEventId());
        assertEquals(baseline.id(), snapshot.initialBaselineEventId());
        assertEquals(List.of(count.id(), laterSale.id()), snapshot.usedEventIds());
        assertEquals(4, history.size());
        assertEquals(sale.id(), history.get(1).id());
    }

    @Test void unconfirmedCountDoesNotReanchor() {
        var snapshot = calculator.calculate("A", List.of(event(0, EventType.STOCK_UPDATED, null, "100", false),
            event(1, EventType.PHYSICAL_COUNT, "80", null, false), event(2, EventType.SALE_CONFIRMED, "5", null, false)));
        assertEquals(0, snapshot.expectedStock().compareTo(new BigDecimal("95")));
        assertNull(snapshot.physicalStock());
        assertNull(snapshot.checkpointEventId());
    }
}
