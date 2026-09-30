package io.peek.core.operational_state;

import io.peek.core.exceptions.ExceptionService;
import io.peek.core.operational_state.StockCalculator.StockSnapshot;
import io.peek.core.operational_state.StockService;
import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.CommandService;
import io.peek.core.orchestration.CommandStatus;
import io.peek.core.products.ProductService;
import io.peek.core.products.ProductService.MappingView;
import io.peek.core.products.ProductService.ProductView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalContextService {
    private final ProductService products;
    private final StockService stocks;
    private final CommandService commands;
    private final ExceptionService exceptions;
    public OperationalContextService(ProductService products, StockService stocks,
                                        CommandService commands, ExceptionService exceptions) {
        this.products = products; this.stocks = stocks; this.commands = commands; this.exceptions = exceptions;
    }
    public record FiscalView(String status, String documentId, String source, Instant confirmedAt,
                             Instant documentOccurredAt, UUID evidenceEventId, UUID commandId) {}
    public record ExecutionView(UUID commandId, String channel, String status, Instant requestedAt,
                                Instant deadlineAt, Instant lastAttemptAt, Instant confirmedAt,
                                List<CommandService.AttemptView> attempts) {}
    public record ContextView(ProductView product, List<MappingView> mappings, StockSnapshot stock,
                               ExecutionView inventorySync, ExecutionView fiscalOrchestration, FiscalView fiscal,
                               List<ExceptionService.ExceptionView> exceptions) {}

    @Transactional(readOnly = true)
    public ContextView forProduct(UUID id) {
        ProductView product = products.get(id);
        var confirmedFiscal = commands.forProduct(id, CommandKind.FISCAL).stream()
            .filter(command -> command.status() == CommandStatus.CONFIRMED).findFirst();
        FiscalView fiscal = confirmedFiscal.map(command -> new FiscalView("CONFIRMED", command.externalDocumentId(),
            command.channel(), command.confirmedAt(), command.confirmationOccurredAt(), command.confirmationEventId(),
            command.id())).orElse(new FiscalView("UNKNOWN", null, null, null, null, null, null));
        return new ContextView(product, products.mappings(id), stocks.forSku(product.sku()),
            execution(id, CommandKind.INVENTORY_SYNC), execution(id, CommandKind.FISCAL), fiscal,
            exceptions.forProduct(id));
    }
    private ExecutionView execution(UUID productId, CommandKind kind) {
        return commands.forProduct(productId, kind).stream().findFirst()
            .map(command -> new ExecutionView(command.id(), command.channel(), command.status().name(),
                command.requestedAt(), command.deadlineAt(),
                command.attempts().get(command.attempts().size() - 1).dispatchedAt(), command.confirmedAt(),
                List.copyOf(command.attempts())))
            .orElse(new ExecutionView(null, null, "NOT_AVAILABLE", null, null, null, null, List.of()));
    }
}
