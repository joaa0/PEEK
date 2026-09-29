package io.peek.core.products;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingAuditRepository extends JpaRepository<MappingAuditEntity, UUID> {
    List<MappingAuditEntity> findByMappingIdOrderByRevision(UUID mappingId);
}
