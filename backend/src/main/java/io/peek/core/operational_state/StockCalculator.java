package io.peek.core.operational_state;

import io.peek.core.events.EventType;
import io.peek.core.events.NormalizedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class StockCalculator {
    public record StockSnapshot(String sku, BigDecimal expectedStock, BigDecimal systemStock,
                                BigDecimal physicalStock, UUID initialBaselineEventId, UUID baselineEventId,
                                UUID checkpointEventId,
                                Instant systemUpdatedAt, Instant physicalCountedAt, List<UUID> usedEventIds) {}

    public StockSnapshot calculate(String sku, List<NormalizedEvent> sourceEvents) {
        BigDecimal expected = null;
        BigDecimal system = null;
        BigDecimal physical = null;
        UUID baseline = null;
        UUID initialBaseline = null;
        UUID checkpoint = null;
        Instant systemAt = null;
        Instant physicalAt = null;
        List<UUID> used = new ArrayList<>();
        List<NormalizedEvent> events = sourceEvents.stream()
            .filter(event -> sku.equals(event.sku()))
            .sorted(Comparator.comparing(NormalizedEvent::occurredAt)
                .thenComparing(NormalizedEvent::receivedAt).thenComparing(NormalizedEvent::id))
            .toList();
        for (NormalizedEvent event : events) {
            switch (event.type()) {
                case STOCK_UPDATED -> {
                    if (event.stockAfter() != null) {
                        system = event.stockAfter();
                        systemAt = event.occurredAt();
                        if (expected == null) {
                            expected = system;
                            baseline = event.id();
                            initialBaseline = event.id();
                            used.add(event.id());
                        }
                    }
                }
                case GOODS_RECEIVED -> { if (expected != null) { expected = expected.add(event.quantity()); used.add(event.id()); } }
                case SALE_CONFIRMED -> { if (expected != null) { expected = expected.subtract(event.quantity()); used.add(event.id()); } }
                case STOCK_ADJUSTED -> { if (expected != null) { expected = expected.add(event.quantity()); used.add(event.id()); } }
                case PHYSICAL_COUNT -> {
                    if (event.confirmed()) {
                        physical = event.quantity();
                        physicalAt = event.occurredAt();
                        expected = physical;
                        baseline = event.id();
                        checkpoint = event.id();
                        used.clear();
                        used.add(event.id());
                    }
                }
                default -> { }
            }
        }
        return new StockSnapshot(sku, expected, system, physical, initialBaseline, baseline, checkpoint,
            systemAt, physicalAt, List.copyOf(used));
    }
}
