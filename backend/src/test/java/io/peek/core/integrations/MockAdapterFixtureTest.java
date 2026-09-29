package io.peek.core.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.peek.core.events.EventType;
import io.peek.core.events.EventValidation;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class MockAdapterFixtureTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private <T> T fixture(String name, Class<T> type) throws IOException {
        try (var stream = getClass().getResourceAsStream("/fixtures/" + name + ".json")) {
            return json.readValue(stream, type);
        }
    }
    @Test void allFictitiousFormatsTranslateIntoCanonicalEvents() throws IOException {
        var sale = new MockSalesAdapter().translate(fixture("mock-sale", MockSalesAdapter.SaleNotice.class));
        var stock = new MockInventoryAdapter().translate(fixture("mock-inventory", MockInventoryAdapter.StockNotice.class));
        var fiscal = new MockFiscalAdapter().translate(fixture("mock-fiscal", MockFiscalAdapter.FiscalNotice.class));
        var physical = new MockPhysicalAdapter().translate(fixture("mock-physical", MockPhysicalAdapter.PhysicalNotice.class));
        var count = new MockPhysicalAdapter().translate(fixture("mock-physical-count", MockPhysicalAdapter.PhysicalNotice.class));
        var adjustment = new MockPhysicalAdapter().translate(fixture("mock-physical-adjustment", MockPhysicalAdapter.PhysicalNotice.class));
        var exit = new MockPhysicalAdapter().translate(fixture("mock-physical-exit", MockPhysicalAdapter.PhysicalNotice.class));
        assertEquals(EventType.SALE_CONFIRMED, EventValidation.validate(sale));
        assertEquals("ORDER-1", sale.orderId());
        assertEquals("ORDER-1", sale.metadata().get("sourceReference"));
        assertEquals(EventType.STOCK_UPDATED, EventValidation.validate(stock));
        assertEquals("ORDER-1", stock.orderId());
        assertEquals("stock-fixture-1", stock.metadata().get("sourceReference"));
        assertEquals(EventType.INVOICE_ISSUED, EventValidation.validate(fiscal));
        assertEquals("INV-1", fiscal.invoiceId());
        assertEquals("INV-1", fiscal.metadata().get("sourceReference"));
        assertEquals(EventType.GOODS_RECEIVED, EventValidation.validate(physical));
        assertEquals("RECEIPT-1", physical.receiptId());
        assertEquals(EventType.PHYSICAL_COUNT, EventValidation.validate(count));
        assertEquals(true, count.confirmed());
        assertEquals(EventType.STOCK_ADJUSTED, EventValidation.validate(adjustment));
        assertEquals("MOVE-ADJ-1", adjustment.movementId());
        assertEquals(EventType.PHYSICAL_EXIT, EventValidation.validate(exit));
        assertEquals("ORDER-1", exit.orderId());
        assertEquals("exit-fixture-1", exit.metadata().get("sourceReference"));
    }
    @Test void unknownPhysicalKindAndMissingFieldsAreExplicit() {
        var unknown = new MockPhysicalAdapter.PhysicalNotice("T1", "physical", "OTHER", "A", null,
            null, null, java.math.BigDecimal.ONE, null, java.time.Instant.EPOCH);
        assertThrows(UnsupportedMockPayloadException.class, () -> new MockPhysicalAdapter().translate(unknown));
        assertThrows(IllegalArgumentException.class, () -> new MockSalesAdapter().translate(null));
        assertThrows(IllegalArgumentException.class, () -> new MockInventoryAdapter().translate(
            new MockInventoryAdapter.StockNotice("C1", "inventory", "SKU-1", null, null,
                null, null, java.time.Instant.EPOCH)));
        assertThrows(IllegalArgumentException.class, () -> new MockFiscalAdapter().translate(
            new MockFiscalAdapter.FiscalNotice("F1", "fiscal", null, "O1", "M1", "SKU-1",
                java.math.BigDecimal.ONE, java.time.Instant.EPOCH)));
        assertThrows(IllegalArgumentException.class, () -> new MockPhysicalAdapter().translate(
            new MockPhysicalAdapter.PhysicalNotice("T2", "physical", "EXIT", "SKU-1", null,
                null, null, java.math.BigDecimal.ONE, null, java.time.Instant.EPOCH)));
    }
}
