package io.peek.core.exceptions;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ExceptionRepository extends JpaRepository<ExceptionEntity, UUID>, JpaSpecificationExecutor<ExceptionEntity> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from ExceptionEntity e where e.id = :id")
    Optional<ExceptionEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    @org.springframework.data.jpa.repository.Query(value = "select id from operational_exception where id = :id for update", nativeQuery = true)
    Optional<UUID> lockId(@org.springframework.data.repository.query.Param("id") UUID id);
    Optional<ExceptionEntity> findByCodeAndTriggerEventId(ExceptionCode code, UUID triggerEventId);
    Optional<ExceptionEntity> findByCodeAndOperationCommandId(ExceptionCode code, UUID operationCommandId);
    Optional<ExceptionEntity> findByCodeAndTriggerEventIdAndOperationCommandIdIsNull(ExceptionCode code, UUID triggerEventId);
}
