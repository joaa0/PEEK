package io.peek.core.exceptions;

import io.peek.core.events.*;
import io.peek.core.operational_state.StockCalculator;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvestigationService {
    private final ExceptionService exceptions;
    private final EventService events;
    private final StockCalculator calculator = new StockCalculator();
    public InvestigationService(ExceptionService exceptions, EventService events) {
        this.exceptions = exceptions; this.events = events;
    }
    public record InvestigationView(ExceptionService.ExceptionView exception, NormalizedEvent trigger,
        NormalizedEvent order, StockCalculator.StockSnapshot stockAtDetection,
        StockCalculator.StockSnapshot currentStock, NormalizedEvent systemSnapshot,
        NormalizedEvent physicalCheckpoint) {}
    @Transactional(readOnly = true)
    public InvestigationView get(UUID id) {
        var alert = exceptions.get(id);
        var trigger = events.get(alert.triggerEventId());
        var all = trigger.sku() == null ? List.<NormalizedEvent>of() : events.forSku(trigger.sku());
        var ordered = all.stream().sorted(Comparator.comparing(NormalizedEvent::occurredAt)
            .thenComparing(NormalizedEvent::receivedAt).thenComparing(NormalizedEvent::id)).toList();
        var atDetection = ordered.stream().filter(e -> !e.occurredAt().isAfter(alert.detectedAt())).toList();
        var comparisonEvents = atDetection;
        if (alert.code() == ExceptionCode.E02) {
            // E02 compares before the triggering count reanchors future calculations.
            int countIndex = atDetection.indexOf(trigger);
            comparisonEvents = countIndex < 0 ? List.of() : atDetection.subList(0, countIndex);
        }
        var stock = calculator.calculate(trigger.sku(), comparisonEvents);
        if (alert.code() == ExceptionCode.E02) {
            stock = new StockCalculator.StockSnapshot(stock.sku(), stock.expectedStock(), stock.systemStock(),
                trigger.quantity(), stock.initialBaselineEventId(), stock.baselineEventId(), trigger.id(),
                stock.systemUpdatedAt(), trigger.occurredAt(), stock.usedEventIds());
        }
        var current = calculator.calculate(trigger.sku(), ordered);
        var snapshot = comparisonEvents.stream().filter(e -> e.type() == EventType.STOCK_UPDATED && e.stockAfter() != null)
            .reduce((a, b) -> b).orElse(null);
        var checkpoint = stock.checkpointEventId() == null ? null : events.get(stock.checkpointEventId());
        var order = trigger.type() == EventType.SALE_CONFIRMED ? trigger : atDetection.stream()
            .filter(e -> e.type() == EventType.SALE_CONFIRMED && trigger.orderId() != null
                && trigger.orderId().equals(e.orderId())).reduce((a, b) -> b).orElse(null);
        return new InvestigationView(alert, trigger, order, stock, current, snapshot, checkpoint);
    }
}
