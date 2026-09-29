package io.peek.core.presentation;

import io.peek.core.events.EventService;
import io.peek.core.events.NormalizedEvent;
import io.peek.core.integrations.MockFiscalAdapter;
import io.peek.core.integrations.MockInventoryAdapter;
import io.peek.core.integrations.MockPhysicalAdapter;
import io.peek.core.integrations.MockSalesAdapter;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mock")
public class MockIntegrationController {
    private final EventService events;
    private final MockSalesAdapter sales;
    private final MockInventoryAdapter inventory;
    private final MockFiscalAdapter fiscal;
    private final MockPhysicalAdapter physical;

    public MockIntegrationController(EventService events, MockSalesAdapter sales, MockInventoryAdapter inventory,
                                     MockFiscalAdapter fiscal, MockPhysicalAdapter physical) {
        this.events = events; this.sales = sales; this.inventory = inventory; this.fiscal = fiscal; this.physical = physical;
    }
    @PostMapping("/sales")
    public ResponseEntity<NormalizedEvent> sale(@RequestBody MockSalesAdapter.SaleNotice notice) {
        return ingest(events.ingest(sales.translate(notice)));
    }
    @PostMapping("/inventory")
    public ResponseEntity<NormalizedEvent> stock(@RequestBody MockInventoryAdapter.StockNotice notice) {
        return ingest(events.ingest(inventory.translate(notice)));
    }
    @PostMapping("/fiscal")
    public ResponseEntity<NormalizedEvent> invoice(@RequestBody MockFiscalAdapter.FiscalNotice notice) {
        return ingest(events.ingest(fiscal.translate(notice)));
    }
    @PostMapping("/physical")
    public ResponseEntity<NormalizedEvent> physical(@RequestBody MockPhysicalAdapter.PhysicalNotice notice) {
        return ingest(events.ingest(physical.translate(notice)));
    }
    private ResponseEntity<NormalizedEvent> ingest(EventService.IngestResult result) {
        URI location = URI.create("/api/v1/events/" + result.event().id());
        return result.created() ? ResponseEntity.created(location).body(result.event())
            : ResponseEntity.ok().location(location).body(result.event());
    }
}
