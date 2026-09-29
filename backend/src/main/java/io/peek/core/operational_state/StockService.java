package io.peek.core.operational_state;

import io.peek.core.events.EventService;
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
}
