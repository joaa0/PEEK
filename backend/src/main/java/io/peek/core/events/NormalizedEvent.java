package io.peek.core.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NormalizedEvent(
    UUID id, String source, String externalEventId, EventType type,
    Instant occurredAt, Instant receivedAt, UUID productId, String sku,
    String externalProductId, String orderId, String invoiceId, String receiptId,
    String movementId, BigDecimal quantity, BigDecimal stockAfter,
    boolean confirmed, Map<String, String> metadata
) {}
