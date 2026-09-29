package io.peek.core.orchestration;

import io.peek.core.configuration.DemoConfigurationRepository;
import io.peek.core.events.EventService;
import io.peek.core.events.EventType;
import io.peek.core.events.NormalizedEvent;
import io.peek.core.operational_state.StockService;
import io.peek.core.products.MappingStatus;
import io.peek.core.products.ProductService;
import io.peek.core.reconciliation.CorrelationPolicy;
import io.peek.core.shared.ConflictException;
import io.peek.core.shared.NotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandService {
    private final CommandRepository commands;
    private final AttemptRepository attempts;
    private final EventService events;
    private final ProductService products;
    private final StockService stock;
    private final DemoConfigurationRepository configurations;
    private final Clock clock;
    private final Map<CommandKind, OutboundCommandAdapter> outboundAdapters;
    private final CorrelationPolicy correlation = new CorrelationPolicy();

    public CommandService(CommandRepository commands, AttemptRepository attempts, EventService events,
                          ProductService products, StockService stock, DemoConfigurationRepository configurations,
                          Clock clock, List<OutboundCommandAdapter> outboundAdapters) {
        this.commands = commands; this.attempts = attempts; this.events = events; this.products = products;
        this.stock = stock; this.configurations = configurations; this.clock = clock;
        this.outboundAdapters = new EnumMap<>(CommandKind.class);
        for (OutboundCommandAdapter adapter : outboundAdapters) {
            if (this.outboundAdapters.put(adapter.kind(), adapter) != null) {
                throw new IllegalStateException("More than one outbound adapter for " + adapter.kind());
            }
        }
    }

    public record CommandInput(UUID triggerEventId, String channel, String idempotencyKey, boolean simulateFailure) {}
    public record AttemptView(UUID id, int attemptNumber, String idempotencyKey, Instant dispatchedAt, Instant respondedAt,
                              String result, String externalRequestId, String errorCode, String errorMessage) {}
    public record CommandView(UUID id, CommandKind kind, String idempotencyKey, UUID triggerEventId,
                              UUID productId, UUID mappingId, String channel, String externalProductId,
                              String sku, String orderId, String movementId, BigDecimal requestedQuantity,
                              BigDecimal expectedStock, Instant requestedAt, Instant deadlineAt, CommandStatus status,
                               UUID confirmationEventId, Instant confirmedAt, Instant confirmationOccurredAt,
                               String externalDocumentId,
                              String lastErrorCode, long version, List<AttemptView> attempts) {}
    public record CreateResult(CommandView command, boolean created) {}

    @Transactional
    public CreateResult create(CommandKind kind, CommandInput input) {
        if (input == null || input.triggerEventId() == null || blank(input.channel()) || blank(input.idempotencyKey()))
            throw new IllegalArgumentException("triggerEventId, channel and idempotencyKey are required");
        if (input.channel().length() > 100 || input.idempotencyKey().length() > 200)
            throw new IllegalArgumentException("channel or idempotencyKey exceeds maximum length");
        var existing = commands.findByKindAndIdempotencyKey(kind, input.idempotencyKey().trim());
        if (existing.isPresent()) {
            CommandEntity prior = existing.get();
            if (!prior.triggerEventId.equals(input.triggerEventId()) || !prior.channel.equals(input.channel().trim()))
                throw new ConflictException("Idempotency key identifies a different command");
            return new CreateResult(view(prior), false);
        }
        NormalizedEvent trigger = events.get(input.triggerEventId());
        EventType required = kind == CommandKind.INVENTORY_SYNC ? EventType.SALE_CONFIRMED : EventType.PHYSICAL_EXIT;
        if (trigger.type() != required || trigger.productId() == null || blank(trigger.sku()) || trigger.quantity() == null)
            throw new IllegalArgumentException("Trigger must be a mapped " + required + " event with quantity");
        CorrelationPolicy.Key key = kind == CommandKind.INVENTORY_SYNC ? CorrelationPolicy.Key.ORDER_ID_AND_SKU
            : fiscalKey();
        String missing = correlation.missingTriggerField(trigger, key);
        if (missing != null) throw new IllegalArgumentException("Trigger lacks correlation key: " + missing);
        var mapping = products.mappings(trigger.productId()).stream()
            .filter(row -> row.channel().equals(input.channel().trim()) && row.status() == MappingStatus.ACTIVE)
            .findFirst().orElseThrow(() -> new ConflictException("Active product mapping is required for channel"));
        var prior = commands.findByKindAndTriggerEventIdAndChannel(kind, trigger.id(), mapping.channel());
        if (prior.isPresent()) throw new ConflictException("Trigger and channel already have a command with another idempotency key");
        var config = configurations.findById((short) 1).orElseThrow();
        CommandEntity command = new CommandEntity();
        command.id = UUID.randomUUID(); command.kind = kind; command.idempotencyKey = input.idempotencyKey().trim();
        command.triggerEventId = trigger.id(); command.productId = trigger.productId(); command.mappingId = mapping.id();
        command.channel = mapping.channel(); command.externalProductId = mapping.externalId(); command.sku = trigger.sku();
        command.orderId = trigger.orderId(); command.movementId = trigger.movementId();
        command.requestedAt = clock.instant();
        command.requestedQuantity = trigger.quantity();
        command.expectedStock = kind == CommandKind.INVENTORY_SYNC
            ? stock.forSkuAsOf(trigger.sku(), command.requestedAt, command.requestedAt).expectedStock() : null;
        command.deadlineAt = command.requestedAt.plusSeconds(kind == CommandKind.INVENTORY_SYNC
            ? config.stockSyncTimeoutSeconds : config.fiscalTimeoutSeconds);
        command.status = CommandStatus.REQUESTED;
        commands.saveAndFlush(command);
        dispatch(command, "initial:" + command.idempotencyKey, input.simulateFailure());
        return new CreateResult(view(command), true);
    }

    @Transactional
    public CommandView retry(UUID id, String idempotencyKey, boolean simulateFailure) {
        if (blank(idempotencyKey)) throw new IllegalArgumentException("Retry idempotencyKey is required");
        if (idempotencyKey.length() > 200) throw new IllegalArgumentException("Retry idempotencyKey exceeds maximum length");
        String normalizedKey = idempotencyKey.trim();
        CommandEntity command = requireForUpdate(id);
        if (attempts.findByCommandIdAndIdempotencyKey(id, normalizedKey).isPresent()) return view(command);
        if (command.status != CommandStatus.FAILED && command.status != CommandStatus.TIMED_OUT)
            throw new ConflictException("Only failed or timed-out commands may be retried");
        var config = configurations.findById((short) 1).orElseThrow();
        command.deadlineAt = clock.instant().plusSeconds(command.kind == CommandKind.INVENTORY_SYNC
            ? config.stockSyncTimeoutSeconds : config.fiscalTimeoutSeconds);
        command.lastErrorCode = null;
        dispatch(command, normalizedKey, simulateFailure);
        return view(command);
    }

    @Transactional(readOnly = true)
    public CommandView get(UUID id) { return view(require(id)); }

    @Transactional(readOnly = true)
    public List<CommandView> forProduct(UUID productId, CommandKind kind) {
        return commands.findByProductIdAndKindOrderByRequestedAtDesc(productId, kind).stream().map(this::view).toList();
    }

    @Transactional
    public void markTimedOut(CommandEntity command, Instant asOf) {
        if (command.status == CommandStatus.PENDING_CONFIRMATION && !asOf.isBefore(command.deadlineAt)) {
            command.status = CommandStatus.TIMED_OUT;
            command.lastErrorCode = "CONFIRMATION_TIMEOUT";
            commands.save(command);
        }
    }

    private void dispatch(CommandEntity command, String idempotencyKey, boolean simulateFailure) {
        Instant now = clock.instant();
        AttemptEntity attempt = new AttemptEntity();
        attempt.id = UUID.randomUUID(); attempt.commandId = command.id;
        attempt.attemptNumber = Math.toIntExact(attempts.countByCommandId(command.id) + 1);
        attempt.idempotencyKey = idempotencyKey;
        attempt.dispatchedAt = now; attempt.respondedAt = now;
        OutboundCommandAdapter adapter = outboundAdapters.get(command.kind);
        if (adapter == null) throw new IllegalStateException("No outbound adapter for " + command.kind);
        var result = adapter.dispatch(new OutboundCommandAdapter.DispatchRequest(command.id, attempt.attemptNumber,
            command.productId, command.mappingId, command.channel, command.externalProductId, command.sku,
            command.orderId, command.movementId, command.requestedQuantity, command.expectedStock, simulateFailure));
        if (!result.accepted()) {
            attempt.result = "FAILED"; attempt.errorCode = result.errorCode();
            attempt.errorMessage = result.errorMessage();
            command.status = CommandStatus.FAILED; command.lastErrorCode = attempt.errorCode;
        } else {
            attempt.result = "ACCEPTED"; attempt.externalRequestId = result.externalRequestId();
            command.status = CommandStatus.PENDING_CONFIRMATION;
        }
        attempts.saveAndFlush(attempt);
        commands.saveAndFlush(command);
    }

    private CorrelationPolicy.Key fiscalKey() {
        var config = configurations.findById((short) 1).orElseThrow();
        return CorrelationPolicy.Key.valueOf(config.fiscalCorrelationKey);
    }
    private CommandEntity require(UUID id) {
        return commands.findById(id).orElseThrow(() -> new NotFoundException("Command not found"));
    }
    private CommandEntity requireForUpdate(UUID id) {
        return commands.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Command not found"));
    }
    private CommandView view(CommandEntity c) {
        var history = attempts.findByCommandIdOrderByAttemptNumber(c.id).stream()
            .map(a -> new AttemptView(a.id, a.attemptNumber, a.idempotencyKey, a.dispatchedAt, a.respondedAt,
                a.result, a.externalRequestId, a.errorCode, a.errorMessage)).toList();
        return new CommandView(c.id, c.kind, c.idempotencyKey, c.triggerEventId, c.productId,
            c.mappingId, c.channel, c.externalProductId, c.sku, c.orderId, c.movementId,
            c.requestedQuantity, c.expectedStock, c.requestedAt, c.deadlineAt, c.status,
            c.confirmationEventId, c.confirmedAt, c.confirmationOccurredAt, c.externalDocumentId,
            c.lastErrorCode, c.version, history);
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
}
