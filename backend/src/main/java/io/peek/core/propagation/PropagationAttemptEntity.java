package io.peek.core.propagation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_propagation_attempt")
public class PropagationAttemptEntity {
    @Id public UUID id;
    public UUID commandId;
    public int attemptNumber;
    public String idempotencyKey;
    public boolean simulateFailure;
    public Instant dispatchedAt;
    public Instant respondedAt;
    public String result;
    public String externalId;
    public String errorCode;
    @Column(columnDefinition = "text") public String errorMessage;
}
