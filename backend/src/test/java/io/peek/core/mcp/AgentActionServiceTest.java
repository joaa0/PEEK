package io.peek.core.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.peek.core.exceptions.*;
import io.peek.core.orchestration.*;
import io.peek.core.shared.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentActionServiceTest {
    private final UUID commandId = UUID.randomUUID();
    private final UUID exceptionId = UUID.randomUUID();
    private CommandService commands;
    private CommandRepository commandRows;
    private ExceptionRepository exceptionRows;
    private ExceptionService exceptions;
    private AgentActionRepository audits;
    private AgentActionService service;
    private CommandEntity row;
    private CommandService.CommandView pending;
    private ExceptionService.ExceptionView exception;

    @BeforeEach void setup() {
        commands = mock(CommandService.class); commandRows = mock(CommandRepository.class);
        exceptionRows = mock(ExceptionRepository.class); exceptions = mock(ExceptionService.class);
        audits = mock(AgentActionRepository.class);
        service = new AgentActionService(commands, commandRows, exceptions, exceptionRows, audits,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), mock(io.peek.core.reconciliation.E02ReconciliationService.class));
        row = mock(CommandEntity.class); row.id = commandId; row.kind = CommandKind.INVENTORY_SYNC;
        when(commandRows.findByIdForUpdate(commandId)).thenReturn(Optional.of(row));
        when(audits.findByCommandIdAndToolNameAndIdempotencyKey(any(), any(), any())).thenReturn(Optional.empty());
        var exRow = mock(ExceptionEntity.class); exRow.id = exceptionId; exRow.code = ExceptionCode.E01;
        exRow.status = ExceptionStatus.OPEN;
        when(exceptionRows.findByCodeAndOperationCommandId(ExceptionCode.E01, commandId)).thenReturn(Optional.of(exRow));
        exception = mock(ExceptionService.ExceptionView.class);
        when(exception.status()).thenReturn(ExceptionStatus.OPEN);
        when(exceptions.get(exceptionId)).thenReturn(exception);
        pending = mock(CommandService.CommandView.class);
        when(pending.status()).thenReturn(CommandStatus.PENDING_CONFIRMATION);
        when(pending.attempts()).thenReturn(List.of());
        when(commands.retry(commandId, "retry-key", false)).thenReturn(pending);
    }

    @Test void reusesCommandServiceRetryAndDoesNotResolveException() {
        var result = service.retryInventory(commandId, " retry-key ");
        verify(commands).retry(commandId, "retry-key", false);
        verify(audits).saveAndFlush(any(AgentActionExecution.class));
        verify(exceptions, never()).resolve(any(), any());
        verify(exceptions, never()).recordInventoryReconciliation(any(), any(), any());
        assertEquals("PENDING_VERIFICATION", result.action().verificationStatus());
        assertEquals("SUCCEEDED", result.action().executionStatus());
        assertFalse(result.replayed());
    }

    @Test void duplicateLogicalActionDoesNotCallRetryOrCreateAnotherAudit() {
        var prior = new AgentActionExecution();
        prior.exceptionId = exceptionId; prior.commandId = commandId; prior.idempotencyKey = "retry-key";
        prior.executionStatus = "SUCCEEDED";
        when(audits.findByCommandIdAndToolNameAndIdempotencyKey(commandId, AgentActionService.RETRY_TOOL, "retry-key"))
            .thenReturn(Optional.of(prior));
        when(commands.get(commandId)).thenReturn(pending);
        assertTrue(service.retryInventory(commandId, "retry-key").replayed());
        verify(commands, never()).retry(any(), any(), anyBoolean());
        verify(audits, never()).saveAndFlush(any());
    }

    @Test void fiscalAndCommandsWithoutOpenE01CannotWrite() {
        row.kind = CommandKind.FISCAL;
        assertThrows(ConflictException.class, () -> service.retryInventory(commandId, "retry-key"));
        row.kind = CommandKind.INVENTORY_SYNC;
        when(exceptionRows.findByCodeAndOperationCommandId(ExceptionCode.E01, commandId)).thenReturn(Optional.empty());
        assertThrows(ConflictException.class, () -> service.retryInventory(commandId, "retry-key"));
        verify(commands, never()).retry(any(), any(), anyBoolean());
    }

    @Test void manualResolutionAndConfirmationAloneAreNotVerified() {
        when(exception.status()).thenReturn(ExceptionStatus.RESOLVED);
        when(pending.status()).thenReturn(CommandStatus.CONFIRMED);
        when(pending.confirmationEventId()).thenReturn(UUID.randomUUID());
        assertEquals("PENDING_VERIFICATION", service.retryInventory(commandId, "retry-key").action().verificationStatus());
    }

    @Test void adapterFailureIsFailedRatherThanPendingOrVerified() {
        when(pending.status()).thenReturn(CommandStatus.FAILED);
        var result = service.retryInventory(commandId, "retry-key");
        assertEquals("FAILED", result.action().executionStatus());
        assertEquals("FAILED", result.action().verificationStatus());
    }
}
