package io.peek.core.integrations;

import io.peek.core.events.EventInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MockSalesAdapter {
    public record SaleNotice(String messageId, String channel, String saleNumber, String itemId,
                             BigDecimal units, Instant happenedAt) {}

    public EventInput translate(SaleNotice notice) {
        if (notice == null || blank(notice.messageId()) || blank(notice.channel()) || blank(notice.saleNumber())
            || blank(notice.itemId()) || notice.units() == null || notice.happenedAt() == null) {
            throw new IllegalArgumentException("Sale notice requires messageId, channel, saleNumber, itemId, units and happenedAt");
        }
        return new EventInput(notice.channel(), notice.messageId(), "SALE_CONFIRMED", notice.happenedAt(),
            null, null, notice.itemId(), notice.saleNumber(), null, null, null, notice.units(), null, null,
            Map.of("sourceReference", notice.saleNumber(), "adapter", "mock-sales"));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
