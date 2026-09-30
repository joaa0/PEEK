package io.peek.core.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Agent request audit; external executions remain in the existing Attempt entity. */
@Entity
@Table(name = "agent_action_execution")
public class AgentActionExecution {
    @Id public UUID id;
    @Column(name = "exception_id") public UUID exceptionId;
    @Column(name = "command_id") public UUID commandId;
    @Column(name = "physical_count_event_id") public UUID physicalCountEventId;
    @Column(name = "decision_fingerprint") public String decisionFingerprint;
    @Column(name = "verification_deadline_at") public Instant verificationDeadlineAt;
    @Column(name = "agent_type") public String agentType;
    @Column(name = "tool_name") public String toolName;
    @Column(name = "action_type") public String actionType;
    @Column(name = "idempotency_key") public String idempotencyKey;
    @Column(name = "started_at") public Instant startedAt;
    @Column(name = "finished_at") public Instant finishedAt;
    @Column(name = "execution_status") public String executionStatus;
    @Column(name = "verification_status") public String verificationStatus;
    @Column(name = "input_summary", columnDefinition = "text") public String inputSummary;
    @Column(name = "output_summary", columnDefinition = "text") public String outputSummary;
    protected AgentActionExecution() {}
}
