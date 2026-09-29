package io.peek.core.products;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_channel_mapping_audit")
public class MappingAuditEntity {
    @Id public UUID id;
    @Column(name = "mapping_id") public UUID mappingId;
    @Column(name = "product_id") public UUID productId;
    public long revision;
    public String operation;
    public String channel;
    @Column(name = "external_id") public String externalId;
    @Enumerated(EnumType.STRING) public MappingStatus status;
    @Column(name = "recorded_at") public Instant recordedAt;
    protected MappingAuditEntity() {}
}
