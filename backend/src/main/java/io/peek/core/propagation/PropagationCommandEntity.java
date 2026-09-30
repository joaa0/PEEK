package io.peek.core.propagation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_propagation_command")
public class PropagationCommandEntity {
    @Id public UUID id;
    public UUID productId;
    public UUID mappingId;
    public String channel;
    public String operation;
    public String idempotencyKey;
    public long productVersion;
    @Column(columnDefinition = "text") public String snapshotJson;
    public boolean simulateFailure;
    public Instant requestedAt;
    public String status;
    public Instant completedAt;
    public String externalId;
    @Version public long version;
}
