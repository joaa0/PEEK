package io.peek.core.presentation;

import io.peek.core.operational_state.StockCalculator.StockSnapshot;
import io.peek.core.operational_state.StockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StockController {
    private final StockService stocks;
    public StockController(StockService stocks) { this.stocks = stocks; }
    @GetMapping("/api/v1/stocks/{sku}")
    public StockSnapshot get(@PathVariable String sku) { return stocks.forSku(sku); }
}
