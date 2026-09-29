package io.peek.core.operational_state;

import io.peek.core.events.EventService;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class StockService {
    private final EventService events;
    private final StockCalculator calculator = new StockCalculator();
    public StockService(EventService events) { this.events = events; }
    public StockCalculator.StockSnapshot forSku(String sku) {
        if (sku == null || sku.isBlank()) throw new IllegalArgumentException("sku is required");
        return calculator.calculate(sku, events.forSku(sku));
    }
    public StockCalculator.StockSnapshot forSkuAsOf(String sku, Instant occurredAt, Instant receivedAt) {
        if (sku == null || sku.isBlank() || occurredAt == null || receivedAt == null) {
            throw new IllegalArgumentException("sku and cutoff timestamps are required");
        }
        return calculator.calculate(sku, events.forSku(sku).stream()
            .filter(event -> !event.occurredAt().isAfter(occurredAt) && !event.receivedAt().isAfter(receivedAt))
            .toList());
    }
}
