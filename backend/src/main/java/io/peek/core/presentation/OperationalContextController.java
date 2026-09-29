package io.peek.core.presentation;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalContextController {
    private final ProductService products;
    private final StockService stocks;
    private final CommandService commands;
    private final ExceptionService exceptions;
    public OperationalContextController(ProductService products, StockService stocks,
                                        CommandService commands, ExceptionService exceptions) {
        this.products = products; this.stocks = stocks; this.commands = commands; this.exceptions = exceptions;
    }
    public record FiscalView(String status, String documentId, String source, Instant confirmedAt,
                             Instant documentOccurredAt, UUID evidenceEventId, UUID commandId) {}
    public record ExecutionView(String status, Instant lastAttemptAt, Instant confirmedAt,
                                List<CommandService.AttemptView> attempts) {}
    public record ContextView(ProductView product, List<MappingView> mappings, StockSnapshot stock,
                               ExecutionView inventorySync, ExecutionView fiscalOrchestration, FiscalView fiscal,
                               List<ExceptionService.ExceptionView> exceptions) {}

    @GetMapping("/api/v1/products/{id}/context")
    public ContextView context(@PathVariable UUID id) {
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
            .map(command -> new ExecutionView(command.status().name(),
                command.attempts().get(command.attempts().size() - 1).dispatchedAt(), command.confirmedAt(),
                List.copyOf(command.attempts())))
            .orElse(new ExecutionView("NOT_AVAILABLE", null, null, List.of()));
    }
}
