package io.peek.core.mcp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentActionRepository extends JpaRepository<AgentActionExecution, UUID> {
    Optional<AgentActionExecution> findByCommandIdAndToolNameAndIdempotencyKey(UUID commandId, String toolName, String key);
    List<AgentActionExecution> findByCommandIdOrderByStartedAtDesc(UUID commandId);
    List<AgentActionExecution> findByExceptionIdOrderByStartedAtDesc(UUID exceptionId);
    Optional<AgentActionExecution> findByExceptionIdAndToolName(UUID exceptionId, String toolName);
    Optional<AgentActionExecution> findByExceptionIdAndToolNameAndMappingId(UUID exceptionId, String toolName, UUID mappingId);
    List<AgentActionExecution> findByToolNameAndVerificationStatus(String toolName, String verificationStatus);
}
