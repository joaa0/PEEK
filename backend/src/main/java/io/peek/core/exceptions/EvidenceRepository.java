package io.peek.core.exceptions;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceRepository extends JpaRepository<EvidenceEntity, UUID> {
    List<EvidenceEntity> findByExceptionIdOrderByPosition(UUID exceptionId);
}
