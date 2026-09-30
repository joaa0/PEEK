package io.peek.core.orchestration;

import io.peek.core.configuration.DemoConfigurationRepository;
import io.peek.core.events.*;
import io.peek.core.exceptions.*;
import io.peek.core.mcp.AgentActionExecution;
import io.peek.core.mcp.AgentActionRepository;
import io.peek.core.operational_state.StockCalculator;
import io.peek.core.products.*;
import io.peek.core.shared.ConflictException;
import io.peek.core.shared.NotFoundException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One backend-derived target, bound to a physical checkpoint and an external identity. */
@Service
public class InventoryCorrectionService {
    public static final String TOOL = "peek_correct_inventory_stock";
    private static final Comparator<NormalizedEvent> EVENT_ORDER = Comparator.comparing(NormalizedEvent::occurredAt)
        .thenComparing(NormalizedEvent::receivedAt).thenComparing(NormalizedEvent::id);
    private final EventService events;
    private final ProductService products;
    private final ProductRepository productRows;
    private final ExceptionService exceptions;
    private final ExceptionRepository exceptionRows;
    private final DemoConfigurationRepository configs;
    private final AgentActionRepository actions;
    private final CommandRepository commandRows;
    private final CommandService commands;
    private final Clock clock;
    private final StockCalculator calculator = new StockCalculator();
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    public InventoryCorrectionService(EventService events, ProductService products, ProductRepository productRows,
        ExceptionService exceptions, ExceptionRepository exceptionRows, DemoConfigurationRepository configs,
        AgentActionRepository actions, CommandRepository commandRows, CommandService commands, Clock clock) {
        this.events = events; this.products = products; this.productRows = productRows;
        this.exceptions = exceptions; this.exceptionRows = exceptionRows; this.configs = configs;
        this.actions = actions; this.commandRows = commandRows; this.commands = commands; this.clock = clock;
    }

    public record Candidate(UUID exceptionId, UUID productId, UUID mappingId, String channel, String externalProductId,
        UUID physicalCountEventId, BigDecimal expectedStock, BigDecimal physicalStock, BigDecimal systemStock,
        BigDecimal tolerance, BigDecimal targetStock, String decisionFingerprint, boolean eligible, String reason) {}
    public record CorrectionResult(CommandService.CommandView command, UUID actionId, String executionStatus,
        String verificationStatus, boolean replayed) {}

    @Transactional(readOnly = true)
    public List<Candidate> candidates(UUID exceptionId) {
        var alert = exceptions.get(exceptionId);
        if (alert.productId() == null || alert.code() != ExceptionCode.E01 && alert.code() != ExceptionCode.E02)
            return List.of();
        return products.mappings(alert.productId()).stream().map(mapping -> inspect(exceptionId, mapping.id(), null)).toList();
    }

    private Candidate inspect(UUID exceptionId, UUID mappingId, UUID ignoredConfirmation) {
        var alert = exceptions.get(exceptionId);
        var product = products.get(alert.productId());
        var mapping = products.mappings(product.id()).stream().filter(m -> m.id().equals(mappingId))
            .findFirst().orElseThrow(() -> new ConflictException("Mapping must belong to the exception product"));
        var all = events.forSku(product.sku()).stream().filter(e -> affectsStock(e.type()) && !e.id().equals(ignoredConfirmation))
            .sorted(EVENT_ORDER).toList();
        var count = all.stream().filter(e -> e.type() == EventType.PHYSICAL_COUNT && e.confirmed())
            .reduce((a, b) -> b).orElse(null);
        var expected = count == null ? null : calculator.calculate(product.sku(), all.subList(0, all.indexOf(count))).expectedStock();
        var physical = count == null ? null : count.quantity();
        var reported = all.stream().filter(e -> e.type() == EventType.STOCK_UPDATED && product.id().equals(e.productId())
            && mapping.channel().equals(e.source()) && Objects.equals(mapping.externalId(), e.externalProductId())
            && e.stockAfter() != null).reduce((a, b) -> b).orElse(null);
        var system = reported == null ? null : reported.stockAfter();
        var config = configs.findById((short) 1).orElseThrow();
        var tolerance = config.physicalStockTolerance;
        String reason = "Eligible: expected and confirmed physical evidence agree; target is expectedStock";
        boolean eligible = true;
        if (alert.status() != ExceptionStatus.OPEN || alert.code() != ExceptionCode.E01 && alert.code() != ExceptionCode.E02
            || !product.active() || !product.sku().equals(alert.sku())) {
            eligible = false; reason = "An OPEN E01/E02 for an active canonical product is required";
        } else if (mapping.status() != MappingStatus.ACTIVE || mapping.externalId() == null) {
            eligible = false; reason = "The external mapping must be ACTIVE";
        } else if (count == null || !product.id().equals(count.productId()) || physical == null || physical.signum() < 0
            || expected == null || expected.signum() < 0 || system == null) {
            eligible = false; reason = "Confirmed physical count, independent expected baseline and target-channel stock are required";
        } else if (expected.subtract(physical).abs().compareTo(tolerance) > 0) {
            eligible = false; reason = "Ambiguous: expectedStock and physicalStock disagree beyond tolerance";
        } else if (system.compareTo(expected) == 0) {
            eligible = false; reason = "Target channel already reports the admissible stock";
        } else if (alert.detectedAt().isAfter(clock.instant()) || all.stream().anyMatch(e -> e.occurredAt().isAfter(clock.instant())
            || e.receivedAt().isAfter(clock.instant()) || !product.id().equals(e.productId()))
            || all.subList(all.indexOf(count) + 1, all.size()).stream().anyMatch(e -> e.type() != EventType.STOCK_UPDATED)) {
            eligible = false; reason = "Physical checkpoint is stale or stock evidence is not current";
        }
        var inventoryCommands = commandRows.findByProductIdAndKindOrderByRequestedAtDesc(product.id(), CommandKind.INVENTORY_SYNC)
            .stream().filter(c -> c.mappingId.equals(mapping.id())).sorted(Comparator.comparing(c -> c.id)).toList();
        inventoryCommands.forEach(entityManager::refresh);
        if (expected != null && inventoryCommands.stream().anyMatch(c -> c.status == CommandStatus.PENDING_CONFIRMATION
            && c.expectedStock != null && c.expectedStock.compareTo(expected) == 0)) {
            eligible = false; reason = "An equivalent inventory write is already pending for this target";
        }
        String input = alert.id() + "|" + alert.version() + "|" + alert.status() + "|" + product.id() + "|" + product.active()
            + "|" + mapping.id() + "|" + mapping.version() + "|" + mapping.channel() + "|" + mapping.externalId()
            + "|" + mapping.status() + "|" + tolerance + "|" + config.stockSyncTimeoutSeconds + "|" + all
            + "|" + inventoryCommands.stream().map(c -> c.id + ":" + c.version + ":" + c.status + ":" + c.expectedStock).toList();
        var prior = count == null ? Optional.<CommandEntity>empty() : commandRows.findByKindAndTriggerEventIdAndChannel(
            CommandKind.INVENTORY_CORRECTION, count.id(), mapping.channel());
        if (ignoredConfirmation == null && prior.isPresent()) {
            eligible = false; reason = "This checkpoint and channel already have a correction; inspect its command";
        }
        return new Candidate(exceptionId, product.id(), mapping.id(), mapping.channel(), mapping.externalId(),
            count == null ? null : count.id(), expected, physical, system, tolerance, eligible ? expected : null,
            fingerprint(input), eligible, reason);
    }

    @Transactional
    public CorrectionResult correct(UUID exceptionId, UUID mappingId, String key, String fingerprint) {
        if (key == null || key.isBlank() || key.length() > 200 || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("A valid idempotencyKey and reviewed decisionFingerprint are required");
        var initial = exceptions.get(exceptionId);
        if (initial.productId() == null) throw new ConflictException("Canonical product required");
        // Event appends and mapping changes acquire this lock too: evidence cannot change during dispatch.
        productRows.lockById(initial.productId()).orElseThrow(() -> new NotFoundException("Product not found"));
        exceptionRows.lockId(exceptionId).orElseThrow();
        var alert = exceptionRows.findById(exceptionId).orElseThrow();
        entityManager.refresh(alert);
        var prior = actions.findByExceptionIdAndToolNameAndMappingId(exceptionId, TOOL, mappingId);
        if (prior.isPresent()) {
            var action = prior.get();
            if (!action.idempotencyKey.equals(key.trim()) || !action.decisionFingerprint.equals(fingerprint))
                throw new ConflictException("This correction already has a different reviewed execution");
            return result(action, true);
        }
        var decision = inspect(exceptionId, mappingId, null);
        if (!decision.eligible() || !decision.decisionFingerprint().equals(fingerprint))
            throw new ConflictException("Correction is ineligible or reviewed evidence changed: " + decision.reason());
        var command = commands.createCorrection(decision, key.trim());
        AgentActionExecution action = new AgentActionExecution();
        action.id = UUID.randomUUID(); action.exceptionId = exceptionId; action.commandId = command.id();
        action.mappingId = mappingId; action.targetStock = decision.targetStock();
        action.physicalCountEventId = decision.physicalCountEventId(); action.decisionFingerprint = fingerprint;
        action.toolName = TOOL; action.actionType = "CORRECT_INVENTORY_STOCK"; action.agentType = "CODEX_EXTERNAL";
        action.idempotencyKey = key.trim(); action.startedAt = command.requestedAt(); action.finishedAt = clock.instant();
        action.verificationDeadlineAt = command.deadlineAt();
        action.executionStatus = command.status() == CommandStatus.FAILED ? "FAILED" : "SUCCEEDED";
        action.verificationStatus = command.status() == CommandStatus.FAILED ? "FAILED" : "PENDING_VERIFICATION";
        action.inputSummary = decision.toString();
        action.outputSummary = "commandStatus=" + command.status() + "; verificationStatus=" + action.verificationStatus;
        actions.saveAndFlush(action);
        return result(action, false);
    }

    /** Invoked only by EvaluationService; dispatch acceptance and status reads never manufacture proof. */
    @Transactional
    public void reconcile(Instant asOf) {
        for (var candidate : actions.findByToolNameAndVerificationStatus(TOOL, "PENDING_VERIFICATION")
            .stream().sorted(Comparator.comparing(a -> a.commandId)).toList()) {
            var initial = commandRows.findById(candidate.commandId).orElseThrow();
            productRows.lockById(initial.productId).orElseThrow();
            // Lock identities before refreshing entities loaded by the earlier detection pass.
            exceptionRows.lockId(candidate.exceptionId).orElseThrow();
            commandRows.lockId(candidate.commandId).orElseThrow();
            var alert = exceptionRows.findById(candidate.exceptionId).orElseThrow();
            var command = commandRows.findById(candidate.commandId).orElseThrow();
            var action = actions.findById(candidate.id).orElseThrow();
            entityManager.refresh(alert); entityManager.refresh(command); entityManager.refresh(action);
            if (!"PENDING_VERIFICATION".equals(action.verificationStatus) || asOf.isBefore(action.finishedAt)) continue;
            try {
                if (asOf.isAfter(action.verificationDeadlineAt) || clock.instant().isAfter(action.verificationDeadlineAt)) {
                    commands.markTimedOut(command, asOf.isAfter(clock.instant()) ? asOf : clock.instant());
                    throw new ConflictException("Confirmation timeout");
                }
                if (!"SUCCEEDED".equals(action.executionStatus) || alert.status != ExceptionStatus.OPEN)
                    throw new ConflictException("Failed execution or exception no longer OPEN");
                var decision = inspect(action.exceptionId, action.mappingId, command.confirmationEventId);
                // The command itself blocks eligibility on a pending read; compare the immutable reviewed fingerprint.
                if (!decision.decisionFingerprint().equals(action.decisionFingerprint)
                    || !Objects.equals(action.physicalCountEventId, decision.physicalCountEventId()))
                    throw new ConflictException("Reviewed evidence or mapping changed before verification");
                if (command.status != CommandStatus.CONFIRMED || command.confirmationEventId == null) continue;
                var confirmation = events.get(command.confirmationEventId);
                if (confirmation.occurredAt().isAfter(asOf) || confirmation.receivedAt().isAfter(asOf)) continue;
                if (confirmation.type() != EventType.STOCK_UPDATED || !command.productId.equals(confirmation.productId())
                    || !command.sku.equals(confirmation.sku()) || !command.channel.equals(confirmation.source())
                    || !command.externalProductId.equals(confirmation.externalProductId())
                    || !command.orderId.equals(confirmation.orderId()) || confirmation.stockAfter() == null
                    || command.expectedStock.compareTo(confirmation.stockAfter()) != 0
                    || action.targetStock.compareTo(confirmation.stockAfter()) != 0
                    || confirmation.occurredAt().isBefore(command.requestedAt)
                    || confirmation.receivedAt().isBefore(command.requestedAt)
                    || confirmation.occurredAt().isAfter(action.verificationDeadlineAt)
                    || confirmation.receivedAt().isAfter(action.verificationDeadlineAt))
                    throw new ConflictException("Confirmation does not prove exact target, identity and freshness");
                exceptions.recordCorrectionReconciliation(alert.id, confirmation.id(), asOf);
                action.verificationStatus = "VERIFIED";
                action.outputSummary = "verificationStatus=VERIFIED; targetStock=" + action.targetStock
                    + "; reconciliationEventId=" + confirmation.id();
            } catch (ConflictException | IllegalArgumentException rejected) {
                action.verificationStatus = "FAILED";
                action.outputSummary = "verificationStatus=FAILED; reason=" + rejected.getMessage();
            }
            actions.saveAndFlush(action);
        }
    }

    private CorrectionResult result(AgentActionExecution action, boolean replayed) {
        return new CorrectionResult(commands.get(action.commandId), action.id, action.executionStatus, action.verificationStatus, replayed);
    }
    private static boolean affectsStock(EventType type) {
        return switch (type) {
            case STOCK_UPDATED, SALE_CONFIRMED, GOODS_RECEIVED, PHYSICAL_COUNT, STOCK_ADJUSTED -> true;
            default -> false;
        };
    }
    private static String fingerprint(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
