package io.peek.core.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "normalized_event")
public class EventEntity {
    @Id public UUID id;
    public String source;
    @Column(name = "external_event_id") public String externalEventId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type") public EventType type;
    @Column(name = "occurred_at") public Instant occurredAt;
    @Column(name = "received_at") public Instant receivedAt;
    @Column(name = "product_id") public UUID productId;
    public String sku;
    @Column(name = "external_product_id") public String externalProductId;
    @Column(name = "order_id") public String orderId;
    @Column(name = "invoice_id") public String invoiceId;
    @Column(name = "receipt_id") public String receiptId;
    @Column(name = "movement_id") public String movementId;
    @Column(precision = 15, scale = 3) public BigDecimal quantity;
    @Column(name = "stock_after", precision = 15, scale = 3) public BigDecimal stockAfter;
    public boolean confirmed;
    @Column(name = "metadata_json", columnDefinition = "text") public String metadataJson;
    @Column(name = "payload_hash") public String payloadHash;

    protected EventEntity() {}
}
