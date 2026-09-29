package io.peek.core.presentation;

import io.peek.core.events.EventService;
import io.peek.core.events.EventType;
import io.peek.core.events.NormalizedEvent;
import io.peek.core.operational_state.StockCalculator.StockSnapshot;
import io.peek.core.operational_state.StockService;
import io.peek.core.products.ProductService;
import io.peek.core.products.ProductService.MappingView;
import io.peek.core.products.ProductService.ProductView;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalContextController {
    private final ProductService products;
    private final StockService stocks;
    private final EventService events;
    public OperationalContextController(ProductService products, StockService stocks, EventService events) {
        this.products = products; this.stocks = stocks; this.events = events;
    }
    public record FiscalView(String status, String documentId, String source, Instant confirmedAt, UUID evidenceEventId) {}
    public record ExecutionView(String status, Instant lastAttemptAt, Instant confirmedAt, List<Object> attempts) {}
    public record ContextView(ProductView product, List<MappingView> mappings, StockSnapshot stock,
                              ExecutionView inventorySync, ExecutionView fiscalOrchestration, FiscalView fiscal) {}

    @GetMapping("/api/v1/products/{id}/context")
    public ContextView context(@PathVariable UUID id) {
        ProductView product = products.get(id);
        var invoices = events.forSku(product.sku()).stream()
            .filter(event -> event.type() == EventType.INVOICE_ISSUED)
            .max(Comparator.comparing(NormalizedEvent::occurredAt));
        FiscalView fiscal = invoices.map(event -> new FiscalView("CONFIRMED", event.invoiceId(), event.source(),
            event.occurredAt(), event.id())).orElse(new FiscalView("UNKNOWN", null, null, null, null));
        ExecutionView notAvailable = new ExecutionView("NOT_AVAILABLE", null, null, List.of());
        return new ContextView(product, products.mappings(id), stocks.forSku(product.sku()), notAvailable, notAvailable, fiscal);
    }
}
