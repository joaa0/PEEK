package io.peek.core.propagation;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropagationAttemptRepository extends JpaRepository<PropagationAttemptEntity, UUID> {
    List<PropagationAttemptEntity> findByCommandIdOrderByAttemptNumber(UUID commandId);
    Optional<PropagationAttemptEntity> findByCommandIdAndIdempotencyKey(UUID commandId, String key);
}
