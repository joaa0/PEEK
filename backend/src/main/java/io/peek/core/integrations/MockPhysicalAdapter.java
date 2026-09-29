package io.peek.core.integrations;

import io.peek.core.events.EventInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MockPhysicalAdapter {
    public record PhysicalNotice(String ticketId, String source, String kind, String sku,
                                 String order, String receipt, String movement, BigDecimal units,
                                 Boolean countConfirmed, Instant registeredAt) {}

    public EventInput translate(PhysicalNotice notice) {
        if (notice == null || blank(notice.ticketId()) || blank(notice.source()) || blank(notice.kind())
            || blank(notice.sku()) || notice.units() == null || notice.registeredAt() == null) {
            throw new IllegalArgumentException("Physical notice requires ticketId, source, kind, sku, units and registeredAt");
        }
        String type = switch (notice.kind()) {
            case "RECEIPT" -> "GOODS_RECEIVED";
            case "COUNT" -> "PHYSICAL_COUNT";
            case "ADJUSTMENT" -> "STOCK_ADJUSTED";
            case "EXIT" -> "PHYSICAL_EXIT";
            default -> throw new UnsupportedMockPayloadException("Unsupported physical notice kind: " + notice.kind());
        };
        if ("EXIT".equals(notice.kind()) && blank(notice.order()) && blank(notice.movement())) {
            throw new IllegalArgumentException("Physical exit requires order or movement reference");
        }
        return new EventInput(notice.source(), notice.ticketId(), type, notice.registeredAt(),
            null, notice.sku(), null, notice.order(), null, notice.receipt(), notice.movement(),
            notice.units(), null, notice.countConfirmed(),
            Map.of("sourceReference", notice.ticketId(), "adapter", "mock-physical"));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
