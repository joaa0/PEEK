package io.peek.core.orchestration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operation_attempt")
public class AttemptEntity {
    @Id public UUID id;
    @Column(name = "command_id") public UUID commandId;
    @Column(name = "attempt_number") public int attemptNumber;
    @Column(name = "dispatched_at") public Instant dispatchedAt;
    @Column(name = "responded_at") public Instant respondedAt;
    public String result;
    @Column(name = "external_request_id") public String externalRequestId;
    @Column(name = "error_code") public String errorCode;
    @Column(name = "error_message") public String errorMessage;
    protected AttemptEntity() {}
}
