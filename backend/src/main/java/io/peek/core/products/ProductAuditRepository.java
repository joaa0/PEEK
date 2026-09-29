package io.peek.core.products;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductAuditRepository extends JpaRepository<ProductAuditEntity, UUID> {
    List<ProductAuditEntity> findByProductIdOrderByRevision(UUID productId);
}
