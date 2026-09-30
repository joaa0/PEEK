package io.peek.core.reconciliation;

import io.peek.core.configuration.DemoConfigurationEntity;
import io.peek.core.configuration.DemoConfigurationRepository;
import io.peek.core.events.EventRepository;
import io.peek.core.events.EventService;
import io.peek.core.events.EventType;
import io.peek.core.events.NormalizedEvent;
import io.peek.core.exceptions.ExceptionCode;
import io.peek.core.exceptions.ExceptionRepository;
import io.peek.core.exceptions.ExceptionService;
import io.peek.core.exceptions.ExceptionService.EvidenceDraft;
import io.peek.core.exceptions.ExceptionService.ExceptionDraft;
import io.peek.core.exceptions.Severity;
import io.peek.core.operational_state.StockCalculator;
import io.peek.core.orchestration.AttemptRepository;
import io.peek.core.orchestration.CommandEntity;
import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.CommandRepository;
import io.peek.core.orchestration.CommandStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {
    private final EventRepository eventRows;
    private final EventService events;
    private final CommandRepository commands;
    private final AttemptRepository attempts;
    private final DemoConfigurationRepository configs;
    private final ExceptionRepository exceptionRows;
    private final ExceptionService exceptions;
    private final E02ReconciliationService physicalReconciliation;
    private final CorrelationPolicy correlation = new CorrelationPolicy();
    private final StockCalculator stock = new StockCalculator();

    public EvaluationService(EventRepository eventRows, EventService events, CommandRepository commands,
                             AttemptRepository attempts, DemoConfigurationRepository configs,
                             ExceptionRepository exceptionRows, ExceptionService exceptions,
                             E02ReconciliationService physicalReconciliation) {
        this.eventRows = eventRows; this.events = events; this.commands = commands; this.attempts = attempts;
        this.configs = configs; this.exceptionRows = exceptionRows; this.exceptions = exceptions;
        this.physicalReconciliation = physicalReconciliation;
    }

    public record EvaluationIssue(UUID triggerEventId, String code, String field) {}
    public record EvaluationResult(Instant asOf, int created, int alreadyPresent, List<UUID> exceptionIds,
                                   List<EvaluationIssue> issues) {}

    @Transactional
    public EvaluationResult evaluate(Instant asOf) {
        if (asOf == null) throw new IllegalArgumentException("asOf is required");
        DemoConfigurationEntity config = configs.findById((short) 1).orElseThrow();
        List<NormalizedEvent> all = eventRows.findAll().stream().map(row -> events.get(row.id))
            .filter(event -> !event.occurredAt().isAfter(asOf))
            .sorted(Comparator.comparing(NormalizedEvent::occurredAt)
                .thenComparing(NormalizedEvent::receivedAt).thenComparing(NormalizedEvent::id)).toList();
        List<UUID> ids = new ArrayList<>();
        List<EvaluationIssue> issues = new ArrayList<>();
        int created = 0;
        int existing = 0;
        for (CommandEntity command : commands.findAll()) {
            if (command.requestedAt.isAfter(asOf)) continue;
            NormalizedEvent trigger = events.get(command.triggerEventId);
            CorrelationPolicy.Key key = command.kind == CommandKind.INVENTORY_SYNC
                ? CorrelationPolicy.Key.ORDER_ID_AND_SKU
                : CorrelationPolicy.Key.valueOf(config.fiscalCorrelationKey);
            String missing = correlation.missingTriggerField(trigger, key);
            if (missing != null) { issues.add(new EvaluationIssue(trigger.id(), "MISSING_CORRELATION_KEY", missing)); continue; }
            EventType expectedType = command.kind == CommandKind.INVENTORY_SYNC ? EventType.STOCK_UPDATED : EventType.INVOICE_ISSUED;
            // Only the engine can close E01. A dispatch response or manual resolution is not proof.
            var priorE01 = command.kind == CommandKind.INVENTORY_SYNC
                ? exceptionRows.findByCodeAndOperationCommandId(ExceptionCode.E01, command.id) : java.util.Optional.<io.peek.core.exceptions.ExceptionEntity>empty();
            if (priorE01.isPresent() && !asOf.isBefore(priorE01.get().detectedAt)
                && command.status == CommandStatus.CONFIRMED
                && command.confirmationEventId != null && command.expectedStock != null) {
                var confirmation = all.stream().filter(event -> event.id().equals(command.confirmationEventId)
                    && !event.receivedAt().isAfter(asOf)).findFirst();
                var history = attempts.findByCommandIdOrderByAttemptNumber(command.id);
                Instant lastDispatch = history.isEmpty() ? command.requestedAt : history.getLast().dispatchedAt;
                if (confirmation.isPresent()) {
                    NormalizedEvent observed = confirmation.get();
                    if (observed.type() == EventType.STOCK_UPDATED
                        && command.channel.equals(observed.source())
                        && command.externalProductId.equals(observed.externalProductId())
                        && !observed.occurredAt().isBefore(lastDispatch)
                        && correlation.compare(trigger, observed, key) == CorrelationPolicy.Match.MATCH
                        && observed.stockAfter() != null
                        && command.expectedStock.compareTo(observed.stockAfter()) == 0) {
                        exceptions.recordInventoryReconciliation(command.id, observed.id(), asOf);
                        continue;
                    }
                }
            }
            List<NormalizedEvent> scopedCandidates = all.stream().filter(candidate -> candidate.type() == expectedType
                && candidate.source().equals(command.channel)
                && command.sku.equals(candidate.sku())
                && command.externalProductId.equals(candidate.externalProductId())
                && !candidate.occurredAt().isBefore(command.requestedAt)
                && !candidate.occurredAt().isAfter(command.deadlineAt)).toList();
            for (NormalizedEvent candidate : scopedCandidates) {
                if (candidate.type() == expectedType && candidate.source().equals(command.channel)
                    && command.sku.equals(candidate.sku()) && correlation.compare(trigger, candidate, key) == CorrelationPolicy.Match.MISSING_KEY) {
                    issues.add(new EvaluationIssue(candidate.id(), "MISSING_CORRELATION_KEY",
                        correlation.missingTriggerField(candidate, key)));
                }
            }
            boolean confirmedInWindow = scopedCandidates.stream().anyMatch(candidate ->
                (expectedType != EventType.STOCK_UPDATED || candidate.stockAfter() != null
                    && (command.expectedStock == null || command.expectedStock.compareTo(candidate.stockAfter()) == 0))
                && (expectedType != EventType.INVOICE_ISSUED || candidate.invoiceId() != null)
                && correlation.compare(trigger, candidate, key) == CorrelationPolicy.Match.MATCH);
            if (confirmedInWindow || asOf.isBefore(command.deadlineAt)) continue;
            ExceptionCode code = command.kind == CommandKind.INVENTORY_SYNC ? ExceptionCode.E01 : ExceptionCode.E03;
            List<EvidenceDraft> evidence = new ArrayList<>();
            evidence.add(evidence(trigger, "TRIGGER", "trigger", trigger.externalEventId()));
            evidence.add(detail("COMMAND", "commandId", command.id.toString(), command.requestedAt));
            evidence.add(detail("COMMAND", "commandStatus", command.status.name(), asOf));
            evidence.add(detail("MAPPING", "mappingId", command.mappingId.toString(), command.requestedAt));
            evidence.add(detail("MAPPING", "channel", command.channel, command.requestedAt));
            evidence.add(detail("MAPPING", "externalProductId", command.externalProductId, command.requestedAt));
            evidence.add(detail("COMMAND", "requestedAt", command.requestedAt.toString(), command.requestedAt));
            evidence.add(detail("PARAMETER", "deadlineAt", command.deadlineAt.toString(), command.deadlineAt));
            var history = attempts.findByCommandIdOrderByAttemptNumber(command.id);
            evidence.add(detail("ATTEMPTS", "count", Integer.toString(history.size()), asOf));
            for (var attempt : history) {
                evidence.add(detail("ATTEMPT", "attempt." + attempt.attemptNumber + ".result", attempt.result, attempt.respondedAt));
                evidence.add(detail("ATTEMPT", "attempt." + attempt.attemptNumber + ".idempotencyKey", attempt.idempotencyKey, attempt.dispatchedAt));
                if (attempt.externalRequestId != null)
                    evidence.add(detail("ATTEMPT", "attempt." + attempt.attemptNumber + ".externalRequestId", attempt.externalRequestId, attempt.respondedAt));
                if (attempt.errorCode != null)
                    evidence.add(detail("ATTEMPT", "attempt." + attempt.attemptNumber + ".errorCode", attempt.errorCode, attempt.respondedAt));
                if (attempt.errorMessage != null)
                    evidence.add(detail("ATTEMPT", "attempt." + attempt.attemptNumber + ".errorMessage", attempt.errorMessage, attempt.respondedAt));
            }
            List<NormalizedEvent> mismatched = scopedCandidates.stream()
                .filter(candidate -> correlation.compare(trigger, candidate, key) == CorrelationPolicy.Match.DIFFERENT).toList();
            for (NormalizedEvent candidate : mismatched) {
                evidence.add(evidence(candidate, "UNMATCHED_CONFIRMATION", "referenceMismatch",
                    candidate.invoiceId() == null ? candidate.externalEventId() : candidate.invoiceId()));
            }
            boolean dispatchFailed = history.stream().anyMatch(attempt -> "FAILED".equals(attempt.result));
            String observed = !mismatched.isEmpty() ? "Confirmation received with a non-matching correlation reference"
                : dispatchFailed ? "Adapter dispatch failed and no correlated confirmation arrived"
                : "No correlated confirmation in process window";
            evidence.add(detail("OBSERVATION", "correlatedConfirmation", observed, asOf));
            if (command.kind == CommandKind.INVENTORY_SYNC && command.expectedStock != null) {
                evidence.add(detail("STATE", "expectedStock", number(command.expectedStock), command.requestedAt));
                var snapshot = stock.calculate(trigger.sku(), all);
                if (snapshot.systemStock() != null)
                    evidence.add(detail("STATE", "systemStock", number(snapshot.systemStock()), snapshot.systemUpdatedAt()));
            }
            String title = code == ExceptionCode.E01 ? "Stock synchronization not confirmed" : "Physical exit without fiscal confirmation";
            String recommendation = code == ExceptionCode.E01 ? "Retry the inventory command or inspect its adapter attempts"
                : "Check the fiscal reference and retry the simulated fiscal command";
            ExceptionDraft draft = new ExceptionDraft(code, trigger.id(), command.id, Severity.CRITICAL, title,
                trigger.productId(), trigger.sku(), trigger.orderId(), expectedType.name() + " by " + command.deadlineAt,
                observed, "timeoutSeconds=" +
                (code == ExceptionCode.E01 ? config.stockSyncTimeoutSeconds : config.fiscalTimeoutSeconds),
                "Operational process remains unconfirmed", recommendation, evidence);
            if (exceptionRows.findByCodeAndOperationCommandId(code, command.id).isPresent()) existing++; else created++;
            ids.add(exceptions.createAt(draft, asOf).id());
            if (command.status == CommandStatus.PENDING_CONFIRMATION) {
                command.status = CommandStatus.TIMED_OUT; command.lastErrorCode = "CONFIRMATION_TIMEOUT";
                commands.save(command);
            }
        }
        for (int index = 0; index < all.size(); index++) {
            NormalizedEvent trigger = all.get(index);
            if (trigger.type() == EventType.PHYSICAL_COUNT && trigger.confirmed()) {
                List<NormalizedEvent> before = all.subList(0, index);
                var snapshot = stock.calculate(trigger.sku(), before);
                if (snapshot.expectedStock() == null) {
                    issues.add(new EvaluationIssue(trigger.id(), "MISSING_STOCK_BASELINE", "expectedStock"));
                    continue;
                }
                BigDecimal difference = trigger.quantity().subtract(snapshot.expectedStock());
                if (difference.abs().compareTo(config.physicalStockTolerance) > 0) {
                    List<EvidenceDraft> evidence = new ArrayList<>();
                    evidence.add(evidence(trigger, "PHYSICAL_COUNT", "physicalCount", number(trigger.quantity())));
                    evidence.add(detail("CALCULATION", "expectedStock", number(snapshot.expectedStock()), trigger.occurredAt()));
                    evidence.add(detail("CALCULATION", "delta", number(difference), trigger.occurredAt()));
                    evidence.add(detail("PARAMETER", "tolerance", number(config.physicalStockTolerance), trigger.occurredAt()));
                    if (snapshot.baselineEventId() != null) {
                        NormalizedEvent baseline = events.get(snapshot.baselineEventId());
                        boolean checkpoint = baseline.type() == EventType.PHYSICAL_COUNT;
                        BigDecimal baselineValue = checkpoint ? baseline.quantity() : baseline.stockAfter();
                        evidence.add(evidence(baseline, checkpoint ? "CHECKPOINT" : "BASELINE",
                            checkpoint ? "physicalCount" : "stockAfter", number(baselineValue)));
                    }
                    for (UUID usedId : snapshot.usedEventIds()) {
                        if (usedId.equals(snapshot.baselineEventId())) continue;
                        NormalizedEvent used = events.get(usedId);
                        evidence.add(evidence(used, "MOVEMENT", used.type().name(), number(used.quantity())));
                    }
                    ExceptionDraft draft = new ExceptionDraft(ExceptionCode.E02, trigger.id(), null, Severity.WARNING,
                        "Physical stock diverges from expected stock", trigger.productId(), trigger.sku(), trigger.orderId(),
                        number(snapshot.expectedStock()), number(trigger.quantity()),
                        "tolerance=" + number(config.physicalStockTolerance),
                        "Inventory balance may be inaccurate; cause is a hypothesis pending investigation",
                        "Recount the item and reconcile movements before adjusting stock", evidence);
                    if (exceptionRows.findByCodeAndTriggerEventIdAndOperationCommandIdIsNull(ExceptionCode.E02, trigger.id()).isPresent()) existing++; else created++;
                    ids.add(exceptions.createAt(draft, asOf).id());
                }
            }
            if (trigger.type() == EventType.GOODS_RECEIVED) {
                String missing = correlation.missingTriggerField(trigger, CorrelationPolicy.Key.RECEIPT_ID_AND_SKU);
                if (missing != null) { issues.add(new EvaluationIssue(trigger.id(), "MISSING_CORRELATION_KEY", missing)); continue; }
                List<NormalizedEvent> registrations = all.stream().filter(candidate -> candidate.type() == EventType.STOCK_UPDATED
                    && candidate.quantity() != null && correlation.compare(trigger, candidate,
                    CorrelationPolicy.Key.RECEIPT_ID_AND_SKU) == CorrelationPolicy.Match.MATCH).toList();
                if (registrations.isEmpty()) continue;
                BigDecimal registered = registrations.stream().map(NormalizedEvent::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal difference = trigger.quantity().subtract(registered);
                if (difference.abs().compareTo(config.receiptTolerance) > 0) {
                    List<EvidenceDraft> evidence = new ArrayList<>();
                    evidence.add(evidence(trigger, "RECEIPT", "receivedQuantity", number(trigger.quantity())));
                    for (NormalizedEvent registration : registrations)
                        evidence.add(evidence(registration, "REGISTRATION", "registeredQuantity", number(registration.quantity())));
                    evidence.add(detail("CALCULATION", "delta", number(difference), asOf));
                    evidence.add(detail("PARAMETER", "tolerance", number(config.receiptTolerance), asOf));
                    ExceptionDraft draft = new ExceptionDraft(ExceptionCode.E04, trigger.id(), null, Severity.WARNING,
                        "Receipt quantity differs from inventory registration", trigger.productId(), trigger.sku(), null,
                        number(trigger.quantity()), number(registered), "tolerance=" + number(config.receiptTolerance),
                        "Receipt and inventory are inconsistent", "Reconcile the receipt and inventory registration", evidence);
                    if (exceptionRows.findByCodeAndTriggerEventIdAndOperationCommandIdIsNull(ExceptionCode.E04, trigger.id()).isPresent()) existing++; else created++;
                    ids.add(exceptions.createAt(draft, asOf).id());
                }
            }
        }
        physicalReconciliation.reconcile(asOf);
        return new EvaluationResult(asOf, created, existing, List.copyOf(ids), List.copyOf(issues));
    }
    private static EvidenceDraft evidence(NormalizedEvent event, String type, String label, String value) {
        return new EvidenceDraft(event.id(), type, event.source(), label, value, event.occurredAt());
    }
    private static EvidenceDraft detail(String type, String label, String value, Instant when) {
        return new EvidenceDraft(null, type, "peek", label, value, when);
    }
    private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
