package io.peek.core.exceptions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operational_exception")
public class ExceptionEntity {
    @Id public UUID id;
    @Enumerated(EnumType.STRING) public ExceptionCode code;
    @Column(name = "trigger_event_id") public UUID triggerEventId;
    @Column(name = "operation_command_id") public UUID operationCommandId;
    @Enumerated(EnumType.STRING) public ExceptionStatus status;
    @Enumerated(EnumType.STRING) public Severity severity;
    public String title;
    @Column(name = "detected_at") public Instant detectedAt;
    @Column(name = "product_id") public UUID productId;
    public String sku;
    @Column(name = "order_id") public String orderId;
    @Column(name = "expected_state", columnDefinition = "text") public String expectedState;
    @Column(name = "observed_state", columnDefinition = "text") public String observedState;
    @Column(name = "rule_parameter", columnDefinition = "text") public String ruleParameter;
    @Column(columnDefinition = "text") public String impact;
    @Column(columnDefinition = "text") public String recommendation;
    @Column(name = "resolution_note", columnDefinition = "text") public String resolutionNote;
    @Column(name = "resolved_at") public Instant resolvedAt;
    @Column(name = "reconciliation_event_id") public UUID reconciliationEventId;
    @Column(name = "reconciled_at") public Instant reconciledAt;
    @Version public long version;
    protected ExceptionEntity() {}
}
