package io.peek.core.products;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product")
public class ProductEntity {
    @Id public UUID id;
    public String sku;
    public String name;
    @Column(columnDefinition = "text") public String description;
    @Column(precision = 15, scale = 2) public BigDecimal price;
    public String gtin;
    public String category;
    public boolean active;
    @Column(name = "created_at") public Instant createdAt;
    @Column(name = "updated_at") public Instant updatedAt;
    @Version public long version;
    protected ProductEntity() {}
}
