package io.peek.core.mcp;

import io.peek.core.exceptions.ExceptionCode;
import io.peek.core.exceptions.ExceptionRepository;
import io.peek.core.exceptions.ExceptionService;
import io.peek.core.exceptions.ExceptionStatus;
import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.CommandRepository;
import io.peek.core.orchestration.CommandService;
import io.peek.core.orchestration.CommandStatus;
import io.peek.core.shared.ConflictException;
import io.peek.core.shared.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentActionService {
    public static final String RETRY_TOOL = "peek_retry_inventory_sync";
    private final CommandService commands;
    private final CommandRepository commandRows;
    private final ExceptionService exceptions;
    private final ExceptionRepository exceptionRows;
    private final AgentActionRepository actions;
    private final Clock clock;

    public AgentActionService(CommandService commands, CommandRepository commandRows, ExceptionService exceptions,
                              ExceptionRepository exceptionRows, AgentActionRepository actions, Clock clock) {
        this.commands = commands; this.commandRows = commandRows; this.exceptions = exceptions;
        this.exceptionRows = exceptionRows; this.actions = actions; this.clock = clock;
    }

    public record ActionView(UUID id, UUID exceptionId, UUID commandId, String agentType, String toolName,
                             String actionType, String idempotencyKey, Instant startedAt, Instant finishedAt,
                             String executionStatus, String verificationStatus, String inputSummary, String outputSummary) {}
    public record RetryResult(CommandService.CommandView command, ActionView action, boolean replayed) {}

    @Transactional
    public RetryResult retryInventory(UUID commandId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200)
            throw new IllegalArgumentException("idempotencyKey must contain 1 to 200 characters");
        String key = idempotencyKey.trim();
        // Reuse the same command-row lock as CommandService, including audit deduplication.
        var command = commandRows.findByIdForUpdate(commandId)
            .orElseThrow(() -> new NotFoundException("Command not found"));
        if (command.kind != CommandKind.INVENTORY_SYNC)
            throw new ConflictException("MCP only permits retry of INVENTORY_SYNC for E01");
        var prior = actions.findByCommandIdAndToolNameAndIdempotencyKey(commandId, RETRY_TOOL, key);
        if (prior.isPresent()) {
            var current = commands.get(commandId);
            refresh(prior.get(), current);
            return new RetryResult(current, view(prior.get()), true);
        }
        var exception = exceptionRows.findByCodeAndOperationCommandId(ExceptionCode.E01, commandId)
            .orElseThrow(() -> new ConflictException("An associated OPEN E01 is required"));
        if (exception.status != ExceptionStatus.OPEN)
            throw new ConflictException("An associated OPEN E01 is required");
        Instant start = clock.instant();
        // All retry eligibility, idempotency, attempts and dispatch remain in CommandService.
        var updated = commands.retry(commandId, key, false);
        AgentActionExecution action = new AgentActionExecution();
        action.id = UUID.randomUUID(); action.exceptionId = exception.id; action.commandId = commandId;
        action.agentType = "CODEX_EXTERNAL"; action.toolName = RETRY_TOOL; action.actionType = "RETRY_INVENTORY_SYNC";
        action.idempotencyKey = key; action.startedAt = start; action.finishedAt = clock.instant();
        action.executionStatus = updated.status() == CommandStatus.FAILED ? "FAILED" : "SUCCEEDED";
        action.inputSummary = "commandId=" + commandId + "; exceptionId=" + exception.id;
        refresh(action, updated);
        actions.saveAndFlush(action);
        return new RetryResult(updated, view(action), false);
    }

    /** Polling updates audit summaries only; it never changes commands or exceptions. */
    @Transactional
    public List<ActionView> forCommand(UUID commandId) {
        var command = commands.get(commandId);
        var rows = actions.findByCommandIdOrderByStartedAtDesc(commandId);
        rows.forEach(action -> refresh(action, command));
        return rows.stream().map(this::view).toList();
    }

    @Transactional
    public List<ActionView> forException(UUID exceptionId) {
        exceptions.get(exceptionId);
        var rows = actions.findByExceptionIdOrderByStartedAtDesc(exceptionId);
        rows.forEach(action -> refresh(action, commands.get(action.commandId)));
        return rows.stream().map(this::view).toList();
    }

    private void refresh(AgentActionExecution action, CommandService.CommandView command) {
        var exception = exceptions.get(action.exceptionId);
        boolean verified = command.status() == CommandStatus.CONFIRMED
            && exception.status() == ExceptionStatus.RESOLVED && exception.reconciledAt() != null
            && command.confirmationEventId() != null
            && command.confirmationEventId().equals(exception.reconciliationEventId());
        // A later successful retry must not rewrite an earlier failed action as VERIFIED.
        var ownAttempt = command.attempts().stream()
            .filter(attempt -> action.idempotencyKey.equals(attempt.idempotencyKey())).findFirst();
        boolean superseded = ownAttempt.isPresent() && !command.attempts().isEmpty()
            && ownAttempt.get().attemptNumber() != command.attempts().getLast().attemptNumber();
        action.verificationStatus = "FAILED".equals(action.executionStatus) || superseded
            || command.status() == CommandStatus.FAILED || command.status() == CommandStatus.TIMED_OUT
            ? "FAILED" : verified ? "VERIFIED" : "PENDING_VERIFICATION";
        action.outputSummary = "commandStatus=" + command.status() + "; exceptionStatus=" + exception.status()
            + "; verificationStatus=" + action.verificationStatus
            + "; confirmationEventId=" + command.confirmationEventId();
    }

    private ActionView view(AgentActionExecution a) {
        return new ActionView(a.id, a.exceptionId, a.commandId, a.agentType, a.toolName, a.actionType,
            a.idempotencyKey, a.startedAt, a.finishedAt, a.executionStatus, a.verificationStatus,
            a.inputSummary, a.outputSummary);
    }
}
