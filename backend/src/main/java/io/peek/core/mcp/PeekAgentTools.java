package io.peek.core.mcp;

import io.peek.core.exceptions.ExceptionCode;
import io.peek.core.exceptions.ExceptionService;
import io.peek.core.exceptions.ExceptionStatus;
import io.peek.core.operational_state.OperationalContextService;
import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.CommandService;
import io.peek.core.orchestration.CommandStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PeekAgentTools {
    private final ExceptionService exceptions;
    private final OperationalContextService context;
    private final CommandService commands;
    private final AgentActionService actions;
    private final io.peek.core.reconciliation.E02ReconciliationService physical;
    private final io.peek.core.exceptions.JevContextService jev;
    private final java.time.Clock clock;
    private final io.peek.core.orchestration.InventoryCorrectionService corrections;

    public PeekAgentTools(ExceptionService exceptions, OperationalContextService context,
                          CommandService commands, AgentActionService actions,
                          io.peek.core.reconciliation.E02ReconciliationService physical,
                          io.peek.core.exceptions.JevContextService jev, java.time.Clock clock,
                          io.peek.core.orchestration.InventoryCorrectionService corrections) {
        this.exceptions = exceptions; this.context = context; this.commands = commands; this.actions = actions;
        this.physical = physical; this.jev = jev; this.clock = clock; this.corrections = corrections;
    }

    public record ExceptionDetails(ExceptionService.ExceptionView exception, CommandService.CommandView command,
                                   boolean retryAllowed, List<AgentActionService.ActionView> agentActions) {}
    public record OperationalContext(OperationalContextService.ContextView context,
                                     List<CommandService.CommandView> relatedCommands,
                                     io.peek.core.reconciliation.E02ReconciliationService.Decision factsAndEvidence,
                                     io.peek.core.exceptions.JevContextService.Interpretation jevInterpretation,
                                     List<io.peek.core.orchestration.InventoryCorrectionService.Candidate> correctionCandidates) {}
    public record CommandDetails(CommandService.CommandView command, List<AgentActionService.ActionView> agentActions) {}
    public record ExceptionState(UUID id, ExceptionCode code, ExceptionStatus status, java.time.Instant resolvedAt,
                                 UUID reconciliationEventId, java.time.Instant reconciledAt,
                                 List<AgentActionService.ActionView> agentActions) {}
    public record ExceptionPage(List<ExceptionService.ExceptionView> exceptions, int total, int nextOffset) {}

    public ExceptionPage listExceptions(ExceptionStatus status, ExceptionCode code, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid pagination");
        var all = exceptions.list(status, code);
        int start = Math.min(offset, all.size());
        int end = start + Math.min(limit, all.size() - start);
        return new ExceptionPage(all.subList(start, end), all.size(), end < all.size() ? end : -1);
    }

    public ExceptionDetails getException(UUID id) {
        var exception = exceptions.get(id);
        var command = exception.operationCommandId() == null ? null : commands.get(exception.operationCommandId());
        boolean eligible = exception.code() == ExceptionCode.E01 && exception.status() == ExceptionStatus.OPEN
            && command != null && command.kind() == CommandKind.INVENTORY_SYNC
            && (command.status() == CommandStatus.FAILED || command.status() == CommandStatus.TIMED_OUT);
        return new ExceptionDetails(exception, command, eligible, actions.forException(id));
    }

    public OperationalContext operationalContext(UUID exceptionId) {
        var exception = exceptions.get(exceptionId);
        if (exception.productId() == null) throw new IllegalArgumentException("Exception has no canonical product");
        var related = new java.util.ArrayList<CommandService.CommandView>();
        related.addAll(commands.forProduct(exception.productId(), CommandKind.INVENTORY_SYNC));
        related.addAll(commands.forProduct(exception.productId(), CommandKind.FISCAL));
        related.addAll(commands.forProduct(exception.productId(), CommandKind.INVENTORY_CORRECTION));
        return new OperationalContext(context.forProduct(exception.productId()), List.copyOf(related),
            exception.code() == ExceptionCode.E02 ? physical.inspect(exceptionId, clock.instant()) : null,
            jev.forException(exception), corrections.candidates(exceptionId));
    }

    public AgentActionService.RetryResult retryInventory(UUID id, String key) {
        return actions.retryInventory(id, key);
    }

    public AgentActionService.PhysicalResult applyE02(UUID id, String key, Boolean approved, String note, String fingerprint) {
        return actions.applyE02(id, key, approved, note, fingerprint);
    }

    public io.peek.core.orchestration.InventoryCorrectionService.CorrectionResult correctInventory(
        UUID exceptionId, UUID mappingId, String key, String fingerprint) {
        return corrections.correct(exceptionId, mappingId, key, fingerprint);
    }

    public CommandDetails commandStatus(UUID id) {
        return new CommandDetails(commands.get(id), actions.forCommand(id));
    }

    public ExceptionState exceptionStatus(UUID id) {
        var exception = exceptions.get(id);
        return new ExceptionState(id, exception.code(), exception.status(), exception.resolvedAt(),
            exception.reconciliationEventId(), exception.reconciledAt(), actions.forException(id));
    }
}
