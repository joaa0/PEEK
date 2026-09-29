package io.peek.core.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operation_command")
public class CommandEntity {
    @Id public UUID id;
    @Enumerated(EnumType.STRING) public CommandKind kind;
    @Column(name = "idempotency_key") public String idempotencyKey;
    @Column(name = "trigger_event_id") public UUID triggerEventId;
    @Column(name = "product_id") public UUID productId;
    @Column(name = "mapping_id") public UUID mappingId;
    public String channel;
    @Column(name = "external_product_id") public String externalProductId;
    public String sku;
    @Column(name = "order_id") public String orderId;
    @Column(name = "movement_id") public String movementId;
    @Column(name = "requested_quantity") public BigDecimal requestedQuantity;
    @Column(name = "expected_stock") public BigDecimal expectedStock;
    @Column(name = "requested_at") public Instant requestedAt;
    @Column(name = "deadline_at") public Instant deadlineAt;
    @Enumerated(EnumType.STRING) public CommandStatus status;
    @Column(name = "confirmation_event_id") public UUID confirmationEventId;
    @Column(name = "confirmed_at") public Instant confirmedAt;
    @Column(name = "external_document_id") public String externalDocumentId;
    @Column(name = "last_error_code") public String lastErrorCode;
    @Version public long version;
    protected CommandEntity() {}
}
