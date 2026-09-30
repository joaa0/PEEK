package io.peek.core.orchestration;

import io.peek.core.configuration.DemoConfigurationRepository;
import io.peek.core.events.NormalizedEvent;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandConfirmationService {
    private final CommandRepository commands;
    private final DemoConfigurationRepository configurations;

    public CommandConfirmationService(CommandRepository commands, DemoConfigurationRepository configurations) {
        this.commands = commands; this.configurations = configurations;
    }

    @Transactional
    public void accept(NormalizedEvent confirmation) {
        CommandKind kind = switch (confirmation.type()) {
            case STOCK_UPDATED -> CommandKind.INVENTORY_SYNC;
            case INVOICE_ISSUED -> CommandKind.FISCAL;
            default -> null;
        };
        if (confirmation.type() == io.peek.core.events.EventType.STOCK_UPDATED) {
            for (var command : commands.findByKindAndStatusIn(CommandKind.INVENTORY_CORRECTION,
                List.of(CommandStatus.PENDING_CONFIRMATION))) {
                if (matches(command, confirmation)) confirm(command, confirmation);
            }
        }
        if (kind == null) return;
        for (CommandEntity command : commands.findByKindAndStatusIn(kind,
            List.of(CommandStatus.PENDING_CONFIRMATION, CommandStatus.TIMED_OUT))) {
            if (!matches(command, confirmation)) continue;
            confirm(command, confirmation);
        }
    }

    private void confirm(CommandEntity command, NormalizedEvent event) {
        command.status = CommandStatus.CONFIRMED; command.confirmationEventId = event.id();
        command.confirmedAt = event.receivedAt(); command.confirmationOccurredAt = event.occurredAt();
        command.externalDocumentId = event.invoiceId(); command.lastErrorCode = null;
        commands.save(command);
    }

    private boolean matches(CommandEntity command, NormalizedEvent event) {
        if (command.kind == CommandKind.INVENTORY_CORRECTION) {
            return command.productId.equals(event.productId()) && command.sku.equals(event.sku())
                && command.channel.equals(event.source()) && command.externalProductId.equals(event.externalProductId())
                && command.orderId.equals(event.orderId()) && event.stockAfter() != null
                && command.expectedStock.compareTo(event.stockAfter()) == 0
                && !event.occurredAt().isBefore(command.requestedAt) && !event.receivedAt().isBefore(command.requestedAt)
                && !event.occurredAt().isAfter(command.deadlineAt) && !event.receivedAt().isAfter(command.deadlineAt);
        }
        if (!command.channel.equals(event.source()) || !command.sku.equals(event.sku())
            || !command.externalProductId.equals(event.externalProductId())
            || event.occurredAt().isBefore(command.requestedAt)) return false;
        if (command.kind == CommandKind.INVENTORY_SYNC) {
            return command.orderId != null && command.orderId.equals(event.orderId()) && event.stockAfter() != null;
        }
        String key = configurations.findById((short) 1).orElseThrow().fiscalCorrelationKey;
        return event.invoiceId() != null && ("MOVEMENT_ID_AND_SKU".equals(key)
            ? command.movementId != null && command.movementId.equals(event.movementId())
            : command.orderId != null && command.orderId.equals(event.orderId()));
    }
}
