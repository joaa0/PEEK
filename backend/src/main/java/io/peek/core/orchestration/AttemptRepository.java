package io.peek.core.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<AttemptEntity, UUID> {
    List<AttemptEntity> findByCommandIdOrderByAttemptNumber(UUID commandId);
    long countByCommandId(UUID commandId);
    Optional<AttemptEntity> findByCommandIdAndIdempotencyKey(UUID commandId, String idempotencyKey);
}
