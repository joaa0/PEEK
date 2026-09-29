package io.peek.core.products;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_audit")
public class ProductAuditEntity {
    @Id public UUID id;
    @Column(name = "product_id") public UUID productId;
    public long revision;
    public String operation;
    public String sku;
    public String name;
    @Column(columnDefinition = "text") public String description;
    @Column(precision = 15, scale = 2) public BigDecimal price;
    public String gtin;
    public String category;
    public boolean active;
    @Column(name = "recorded_at") public Instant recordedAt;
    protected ProductAuditEntity() {}
}
