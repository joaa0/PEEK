package io.peek.core.reconciliation;

import io.peek.core.configuration.DemoConfigurationRepository;
import io.peek.core.events.*;
import io.peek.core.exceptions.*;
import io.peek.core.mcp.AgentActionExecution;
import io.peek.core.mcp.AgentActionRepository;
import io.peek.core.operational_state.StockCalculator;
import io.peek.core.shared.ConflictException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Acknowledges the domain's existing confirmed physical checkpoint; never manufactures a movement. */
@Service
public class E02ReconciliationService {
    public static final String TOOL = "peek_apply_e02_reconciliation";
    private final EventService events;
    private final ExceptionService exceptions;
    private final ExceptionRepository exceptionRows;
    private final DemoConfigurationRepository configs;
    private final AgentActionRepository actions;
    private final java.time.Clock clock;
    private final StockCalculator calculator = new StockCalculator();
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    public E02ReconciliationService(EventService events, ExceptionService exceptions,
        ExceptionRepository exceptionRows, DemoConfigurationRepository configs, AgentActionRepository actions, java.time.Clock clock) {
        this.events = events; this.exceptions = exceptions; this.exceptionRows = exceptionRows;
        this.configs = configs; this.actions = actions;
        this.clock = clock;
    }

    public record Decision(UUID exceptionId, UUID physicalCountEventId, BigDecimal expectedStock,
        BigDecimal physicalStock, BigDecimal delta, BigDecimal tolerance,
        StockCalculator.StockSnapshot checkpointState, List<NormalizedEvent> relevantEvents,
        List<ExceptionService.EvidenceView> evidence, String decisionFingerprint,
        boolean applicable, String recommendedAction, boolean humanApprovalRequired) {}

    @Transactional(readOnly = true)
    public Decision inspect(UUID id, Instant asOf) {
        var alert = exceptions.get(id);
        if (alert.code() != ExceptionCode.E02) throw new ConflictException("This action only supports E02");
        var count = events.get(alert.triggerEventId());
        if (count.type() != EventType.PHYSICAL_COUNT || !count.confirmed() || count.quantity() == null
            || count.quantity().signum() < 0 || count.sku() == null || alert.productId() == null
            || !alert.productId().equals(count.productId()) || !alert.sku().equals(count.sku()))
            throw new ConflictException("An existing valid confirmed physical count is required");
        var all = events.forSku(alert.sku()).stream().sorted(Comparator.comparing(NormalizedEvent::occurredAt)
            .thenComparing(NormalizedEvent::receivedAt).thenComparing(NormalizedEvent::id)).toList();
        int index = all.indexOf(count);
        var before = calculator.calculate(alert.sku(), all.subList(0, index));
        if (before.expectedStock() == null) throw new ConflictException("Physical count lacks an expected-stock baseline");
        var current = calculator.calculate(alert.sku(), all);
        var tolerance = configs.findById((short) 1).orElseThrow().physicalStockTolerance;
        var delta = count.quantity().subtract(before.expectedStock());
        // A changed/later checkpoint, late movement, or configuration needs a fresh human review.
        boolean sameEvidence = before.expectedStock().compareTo(new BigDecimal(alert.expectedState())) == 0
            && count.quantity().compareTo(new BigDecimal(alert.observedState())) == 0;
        boolean applicable = alert.status() == ExceptionStatus.OPEN && sameEvidence
            && delta.abs().compareTo(tolerance) > 0 && count.id().equals(current.checkpointEventId())
            && !alert.detectedAt().isAfter(asOf)
            && all.stream().noneMatch(e -> e.occurredAt().isAfter(asOf) || e.receivedAt().isAfter(asOf));
        String input = alert.id() + "|" + alert.version() + "|" + tolerance.toPlainString() + "|"
            + all.stream().map(e -> e.id() + ":" + e.type() + ":" + e.quantity() + ":" + e.stockAfter()
                + ":" + e.confirmed() + ":" + e.occurredAt() + ":" + e.receivedAt()).toList();
        return new Decision(id, count.id(), before.expectedStock(), count.quantity(), delta, tolerance,
            current, all, alert.evidence(), fingerprint(input), applicable,
            "Accept existing confirmed physical count " + count.id() + " (" + count.quantity()
                + ") as the checkpoint for expected-stock reconstruction; preserve prior movements. "
                + "This does not adjust an external inventory system or confirm a cause.", true);
    }

    public Decision requireApplicable(UUID id, String fingerprint, Instant asOf) {
        var decision = inspect(id, asOf);
        if (!decision.applicable() || !decision.decisionFingerprint().equals(fingerprint))
            throw new ConflictException("E02 evidence changed or action is no longer applicable; review and request approval again");
        return decision;
    }

    /** Called by EvaluationService, in a later transaction than acceptance. */
    @Transactional
    public void reconcile(Instant asOf) {
        var pending = actions.findByToolNameAndVerificationStatus(TOOL, "PENDING_VERIFICATION").stream()
            .sorted(Comparator.comparing(a -> a.exceptionId)).toList();
        for (var candidate : pending) {
            // Detection may already have loaded this entity before a competing engine completed it.
            // Lock the row ID first and reload its version instead of locking a stale managed entity.
            exceptionRows.lockId(candidate.exceptionId).orElseThrow();
            var alert = exceptionRows.findById(candidate.exceptionId).orElseThrow();
            entityManager.refresh(alert);
            // Reload after the exception lock: concurrent evaluations may have completed the action.
            var action = actions.findById(candidate.id).orElseThrow();
            entityManager.refresh(action);
            if (!"PENDING_VERIFICATION".equals(action.verificationStatus) || asOf.isBefore(action.finishedAt)) continue;
            try {
                if (asOf.isAfter(action.verificationDeadlineAt) || clock.instant().isAfter(action.verificationDeadlineAt))
                    throw new ConflictException("Verification timeout");
                if (!"SUCCEEDED".equals(action.executionStatus)) throw new ConflictException("Action failed");
                var decision = requireApplicable(alert.id, action.decisionFingerprint, asOf);
                if (!action.physicalCountEventId.equals(decision.physicalCountEventId()))
                    throw new ConflictException("Checkpoint does not match the approved decision");
                // The same canonical calculator must reconstruct a coherent checkpoint at the count instant.
                var count = events.get(action.physicalCountEventId);
                var atCount = decision.relevantEvents().stream()
                    .filter(e -> !e.occurredAt().isAfter(count.occurredAt())) .toList();
                var state = calculator.calculate(alert.sku, atCount);
                if (!count.id().equals(state.checkpointEventId()) || state.expectedStock() == null
                    || state.expectedStock().subtract(count.quantity()).abs().compareTo(decision.tolerance()) > 0)
                    throw new ConflictException("Checkpoint reconstruction is inconsistent");
                exceptions.recordPhysicalReconciliation(alert.id, count.id(), asOf);
                action.verificationStatus = "VERIFIED";
                action.outputSummary = "Accepted existing checkpoint; verificationStatus=VERIFIED; reconciliationEventId=" + count.id();
            } catch (ConflictException | IllegalArgumentException invalid) {
                action.verificationStatus = "FAILED";
                action.outputSummary = "verificationStatus=FAILED; reason=" + invalid.getMessage();
            }
            actions.saveAndFlush(action);
        }
    }

    private static String fingerprint(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
