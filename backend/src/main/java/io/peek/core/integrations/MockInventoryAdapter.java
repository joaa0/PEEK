package io.peek.core.integrations;

import io.peek.core.events.EventInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MockInventoryAdapter {
    public record StockNotice(String changeId, String system, String warehouseSku, String orderRef,
                              String receiptRef, BigDecimal registeredUnits, BigDecimal onHand,
                              Instant occurredUtc) {}

    public EventInput translate(StockNotice notice) {
        if (notice == null || blank(notice.changeId()) || blank(notice.system()) || blank(notice.warehouseSku())
            || notice.occurredUtc() == null || (notice.registeredUnits() == null && notice.onHand() == null)) {
            throw new IllegalArgumentException("Stock notice requires changeId, system, warehouseSku, occurredUtc and quantity/onHand");
        }
        return new EventInput(notice.system(), notice.changeId(), "STOCK_UPDATED", notice.occurredUtc(),
            null, null, notice.warehouseSku(), notice.orderRef(), null, notice.receiptRef(), notice.changeId(),
            notice.registeredUnits(), notice.onHand(), null,
            Map.of("sourceReference", notice.changeId(), "adapter", "mock-inventory"));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
