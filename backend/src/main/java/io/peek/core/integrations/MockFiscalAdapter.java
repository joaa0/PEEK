package io.peek.core.integrations;

import io.peek.core.events.EventInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MockFiscalAdapter {
    public record FiscalNotice(String notificationId, String system, String documentNumber,
                               String orderReference, String movementReference, String itemCode,
                               BigDecimal pieces, Instant issuedAt) {}

    public EventInput translate(FiscalNotice notice) {
        if (notice == null || blank(notice.notificationId()) || blank(notice.system()) || blank(notice.documentNumber())
            || blank(notice.itemCode()) || notice.issuedAt() == null) {
            throw new IllegalArgumentException("Fiscal notice requires notificationId, system, documentNumber, itemCode and issuedAt");
        }
        return new EventInput(notice.system(), notice.notificationId(), "INVOICE_ISSUED", notice.issuedAt(),
            null, null, notice.itemCode(), notice.orderReference(), notice.documentNumber(), null,
            notice.movementReference(), notice.pieces(), null, null,
            Map.of("sourceReference", notice.documentNumber(), "adapter", "mock-fiscal"));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
