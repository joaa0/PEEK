package io.peek.core.exceptions;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ExceptionRepository extends JpaRepository<ExceptionEntity, UUID>, JpaSpecificationExecutor<ExceptionEntity> {
    Optional<ExceptionEntity> findByCodeAndTriggerEventId(ExceptionCode code, UUID triggerEventId);
    Optional<ExceptionEntity> findByCodeAndOperationCommandId(ExceptionCode code, UUID operationCommandId);
    Optional<ExceptionEntity> findByCodeAndTriggerEventIdAndOperationCommandIdIsNull(ExceptionCode code, UUID triggerEventId);
}
