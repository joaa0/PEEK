package io.peek.core.products;

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
@Table(name = "product_channel_mapping")
public class MappingEntity {
    @Id public UUID id;
    @Column(name = "product_id") public UUID productId;
    public String channel;
    @Column(name = "external_id") public String externalId;
    @Enumerated(EnumType.STRING) public MappingStatus status;
    @Column(name = "created_at") public Instant createdAt;
    @Column(name = "updated_at") public Instant updatedAt;
    @Version public long version;
    protected MappingEntity() {}
}
