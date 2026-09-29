package io.peek.core.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface CommandRepository extends JpaRepository<CommandEntity, UUID> {
    Optional<CommandEntity> findByKindAndIdempotencyKey(CommandKind kind, String idempotencyKey);
    Optional<CommandEntity> findByKindAndTriggerEventIdAndChannel(CommandKind kind, UUID triggerEventId, String channel);
    List<CommandEntity> findByKindAndStatusIn(CommandKind kind, List<CommandStatus> statuses);
    List<CommandEntity> findByProductIdAndKindOrderByRequestedAtDesc(UUID productId, CommandKind kind);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select command from CommandEntity command where command.id = :id")
    Optional<CommandEntity> findByIdForUpdate(@Param("id") UUID id);
}
