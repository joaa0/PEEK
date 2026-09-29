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
    private final CorrelationPolicy correlation = new CorrelationPolicy();
    private final StockCalculator stock = new StockCalculator();

    public EvaluationService(EventRepository eventRows, EventService events, CommandRepository commands,
                             AttemptRepository attempts, DemoConfigurationRepository configs,
                             ExceptionRepository exceptionRows, ExceptionService exceptions) {
        this.eventRows = eventRows; this.events = events; this.commands = commands; this.attempts = attempts;
        this.configs = configs; this.exceptionRows = exceptionRows; this.exceptions = exceptions;
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
            for (NormalizedEvent candidate : all) {
                if (candidate.type() == expectedType && candidate.source().equals(command.channel)
                    && command.sku.equals(candidate.sku()) && correlation.compare(trigger, candidate, key) == CorrelationPolicy.Match.MISSING_KEY) {
                    issues.add(new EvaluationIssue(candidate.id(), "MISSING_CORRELATION_KEY",
                        correlation.missingTriggerField(candidate, key)));
                }
            }
            boolean confirmedInWindow = all.stream().anyMatch(candidate -> candidate.type() == expectedType
                && candidate.source().equals(command.channel)
                && command.externalProductId.equals(candidate.externalProductId())
                && !candidate.occurredAt().isBefore(command.requestedAt)
                && !candidate.occurredAt().isAfter(command.deadlineAt)
                && (expectedType != EventType.STOCK_UPDATED || candidate.stockAfter() != null)
                && (expectedType != EventType.INVOICE_ISSUED || candidate.invoiceId() != null)
                && correlation.compare(trigger, candidate, key) == CorrelationPolicy.Match.MATCH);
            if (confirmedInWindow || asOf.isBefore(command.deadlineAt)) continue;
            ExceptionCode code = command.kind == CommandKind.INVENTORY_SYNC ? ExceptionCode.E01 : ExceptionCode.E03;
            List<EvidenceDraft> evidence = new ArrayList<>();
            evidence.add(evidence(trigger, "TRIGGER", "trigger", trigger.externalEventId()));
            evidence.add(detail("COMMAND", "commandId", command.id.toString(), command.requestedAt));
            evidence.add(detail("PARAMETER", "deadlineAt", command.deadlineAt.toString(), command.deadlineAt));
            evidence.add(detail("OBSERVATION", "correlatedConfirmation", "absent within window", asOf));
            evidence.add(detail("ATTEMPTS", "count", Long.toString(attempts.countByCommandId(command.id)), asOf));
            if (command.kind == CommandKind.INVENTORY_SYNC && command.expectedStock != null)
                evidence.add(detail("STATE", "expectedStock", number(command.expectedStock), command.requestedAt));
            String title = code == ExceptionCode.E01 ? "Stock synchronization not confirmed" : "Physical exit without fiscal confirmation";
            String recommendation = code == ExceptionCode.E01 ? "Retry the inventory command or inspect its adapter attempts"
                : "Check the fiscal reference and retry the simulated fiscal command";
            ExceptionDraft draft = new ExceptionDraft(code, trigger.id(), Severity.CRITICAL, title,
                trigger.productId(), trigger.sku(), trigger.orderId(), expectedType.name() + " by " + command.deadlineAt,
                "No correlated confirmation in process window", "timeoutSeconds=" +
                (code == ExceptionCode.E01 ? config.stockSyncTimeoutSeconds : config.fiscalTimeoutSeconds),
                "Operational process remains unconfirmed", recommendation, evidence);
            if (exceptionRows.findByCodeAndTriggerEventId(code, trigger.id()).isPresent()) existing++; else created++;
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
                        evidence.add(evidence(baseline, "BASELINE", "stockAfter", number(baseline.stockAfter())));
                    }
                    for (UUID usedId : snapshot.usedEventIds()) {
                        if (usedId.equals(snapshot.baselineEventId())) continue;
                        NormalizedEvent used = events.get(usedId);
                        evidence.add(evidence(used, "MOVEMENT", used.type().name(), number(used.quantity())));
                    }
                    ExceptionDraft draft = new ExceptionDraft(ExceptionCode.E02, trigger.id(), Severity.WARNING,
                        "Physical stock diverges from expected stock", trigger.productId(), trigger.sku(), trigger.orderId(),
                        number(snapshot.expectedStock()), number(trigger.quantity()),
                        "tolerance=" + number(config.physicalStockTolerance),
                        "Inventory balance may be inaccurate; cause is a hypothesis pending investigation",
                        "Recount the item and reconcile movements before adjusting stock", evidence);
                    if (exceptionRows.findByCodeAndTriggerEventId(ExceptionCode.E02, trigger.id()).isPresent()) existing++; else created++;
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
                    ExceptionDraft draft = new ExceptionDraft(ExceptionCode.E04, trigger.id(), Severity.WARNING,
                        "Receipt quantity differs from inventory registration", trigger.productId(), trigger.sku(), null,
                        number(trigger.quantity()), number(registered), "tolerance=" + number(config.receiptTolerance),
                        "Receipt and inventory are inconsistent", "Reconcile the receipt and inventory registration", evidence);
                    if (exceptionRows.findByCodeAndTriggerEventId(ExceptionCode.E04, trigger.id()).isPresent()) existing++; else created++;
                    ids.add(exceptions.createAt(draft, asOf).id());
                }
            }
        }
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
