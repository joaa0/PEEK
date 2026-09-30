package io.peek.core.products;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from ProductEntity p where p.id = :id")
    Optional<ProductEntity> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
    Optional<ProductEntity> findBySku(String sku);
}
