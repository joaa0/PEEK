package io.peek.core.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventInput(
    String source, String externalEventId, String type, Instant occurredAt,
    UUID productId, String sku, String externalProductId, String orderId,
    String invoiceId, String receiptId, String movementId, BigDecimal quantity,
    BigDecimal stockAfter, Boolean confirmed, Map<String, String> metadata
) {}
