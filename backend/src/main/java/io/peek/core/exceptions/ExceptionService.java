package io.peek.core.exceptions;

import io.peek.core.events.EventRepository;
import io.peek.core.shared.ConflictException;
import io.peek.core.shared.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExceptionService {
    private final ExceptionRepository exceptions;
    private final EvidenceRepository evidence;
    private final EventRepository events;
    private final Clock clock;

    public ExceptionService(ExceptionRepository exceptions, EvidenceRepository evidence, EventRepository events, Clock clock) {
        this.exceptions = exceptions; this.evidence = evidence; this.events = events; this.clock = clock;
    }

    public record EvidenceDraft(UUID eventId, String type, String source, String label, String value, Instant occurredAt) {}
    public record ExceptionDraft(ExceptionCode code, UUID triggerEventId, UUID operationCommandId,
                                 Severity severity, String title,
                                 UUID productId, String sku, String orderId, String expectedState,
                                 String observedState, String ruleParameter, String impact,
                                 String recommendation, List<EvidenceDraft> evidence) {}
    public record EvidenceView(UUID id, UUID eventId, String type, String source, String label, String value, Instant occurredAt) {}
    public record ExceptionView(UUID id, ExceptionCode code, UUID triggerEventId, UUID operationCommandId,
                                ExceptionStatus status,
                                Severity severity, String title, Instant detectedAt, UUID productId,
                                String sku, String orderId, String expectedState, String observedState,
                                String ruleParameter, String impact, String recommendation,
                                String resolutionNote, Instant resolvedAt, long version, List<EvidenceView> evidence,
                                UUID reconciliationEventId, Instant reconciledAt) {}

    @Transactional
    public ExceptionView create(ExceptionDraft draft) {
        return createAt(draft, clock.instant());
    }

    @Transactional
    public ExceptionView createAt(ExceptionDraft draft, Instant detectedAt) {
        validate(draft);
        if (detectedAt == null) throw new IllegalArgumentException("detectedAt is required");
        var existing = existing(draft);
        if (existing.isPresent()) return view(existing.get());
        if (!events.existsById(draft.triggerEventId())) throw new NotFoundException("Trigger event not found");
        ExceptionEntity entity = new ExceptionEntity();
        entity.id = UUID.randomUUID();
        entity.code = draft.code();
        entity.triggerEventId = draft.triggerEventId();
        entity.operationCommandId = draft.operationCommandId();
        entity.status = ExceptionStatus.OPEN;
        entity.severity = draft.severity();
        entity.title = draft.title();
        entity.detectedAt = detectedAt;
        entity.productId = draft.productId();
        entity.sku = draft.sku();
        entity.orderId = draft.orderId();
        entity.expectedState = draft.expectedState();
        entity.observedState = draft.observedState();
        entity.ruleParameter = draft.ruleParameter();
        entity.impact = draft.impact();
        entity.recommendation = draft.recommendation();
        exceptions.saveAndFlush(entity);
        List<EvidenceEntity> rows = new ArrayList<>();
        for (int index = 0; index < draft.evidence().size(); index++) {
            EvidenceDraft item = draft.evidence().get(index);
            if (item.eventId() != null && !events.existsById(item.eventId())) throw new NotFoundException("Evidence event not found");
            EvidenceEntity row = new EvidenceEntity();
            row.id = UUID.randomUUID(); row.exceptionId = entity.id; row.eventId = item.eventId();
            row.type = item.type(); row.source = item.source(); row.label = item.label();
            row.value = item.value(); row.occurredAt = item.occurredAt(); row.position = index;
            rows.add(row);
        }
        evidence.saveAllAndFlush(rows);
        return view(entity);
    }

    @Transactional(readOnly = true)
    public List<ExceptionView> list(ExceptionStatus status, ExceptionCode code) {
        Specification<ExceptionEntity> criteria = (root, query, cb) -> cb.conjunction();
        if (status != null) criteria = criteria.and((root, query, cb) -> cb.equal(root.get("status"), status));
        if (code != null) criteria = criteria.and((root, query, cb) -> cb.equal(root.get("code"), code));
        return exceptions.findAll(criteria, Sort.by(Sort.Direction.DESC, "detectedAt", "id"))
            .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ExceptionView get(UUID id) { return view(require(id)); }

    @Transactional(readOnly = true)
    public List<ExceptionView> forProduct(UUID productId) {
        if (productId == null) throw new IllegalArgumentException("productId is required");
        Specification<ExceptionEntity> criteria = (root, query, cb) -> cb.equal(root.get("productId"), productId);
        return exceptions.findAll(criteria, Sort.by(Sort.Direction.DESC, "detectedAt", "id"))
            .stream().map(this::view).toList();
    }

    @Transactional
    public ExceptionView resolve(UUID id, String note) {
        if (note == null || note.isBlank()) throw new IllegalArgumentException("Resolution note is required");
        ExceptionEntity entity = require(id);
        if (entity.status == ExceptionStatus.RESOLVED) {
            if (!entity.resolutionNote.equals(note.trim())) throw new ConflictException("Exception was resolved with a different note");
            return view(entity);
        }
        entity.status = ExceptionStatus.RESOLVED;
        entity.resolutionNote = note.trim();
        entity.resolvedAt = clock.instant();
        exceptions.flush();
        return view(entity);
    }

    /** Called only by deterministic reconciliation after validating the confirmation. */
    @Transactional
    public void recordInventoryReconciliation(UUID commandId, UUID confirmationId, Instant asOf) {
        var found = exceptions.findByCodeAndOperationCommandId(ExceptionCode.E01, commandId);
        if (found.isEmpty()) return;
        ExceptionEntity entity = found.get();
        if (entity.status != ExceptionStatus.OPEN) return;
        var confirmation = events.findById(confirmationId).orElseThrow();
        entity.status = ExceptionStatus.RESOLVED;
        entity.resolvedAt = asOf;
        entity.resolutionNote = "Reconciliation confirmed inventory stock; event=" + confirmationId;
        entity.reconciliationEventId = confirmationId;
        entity.reconciledAt = asOf;
        EvidenceEntity proof = new EvidenceEntity();
        proof.id = UUID.randomUUID(); proof.exceptionId = entity.id; proof.eventId = confirmationId;
        proof.type = "RECONCILIATION"; proof.source = confirmation.source;
        proof.label = "verifiedStock"; proof.value = confirmation.stockAfter.toPlainString();
        proof.occurredAt = confirmation.occurredAt;
        proof.position = evidence.findByExceptionIdOrderByPosition(entity.id).size();
        evidence.saveAndFlush(proof);
        exceptions.flush();
    }

    /** Engine-only correction proof; the original exception and all its evidence remain auditable. */
    @Transactional
    public void recordCorrectionReconciliation(UUID exceptionId, UUID confirmationId, Instant asOf) {
        var entity = exceptions.findByIdForUpdate(exceptionId).orElseThrow();
        if (entity.status != ExceptionStatus.OPEN || entity.code != ExceptionCode.E01 && entity.code != ExceptionCode.E02)
            throw new ConflictException("Correction proof requires an OPEN E01/E02");
        var confirmation = events.findById(confirmationId).orElseThrow();
        entity.status = ExceptionStatus.RESOLVED; entity.resolvedAt = asOf;
        entity.resolutionNote = "Guarded inventory correction verified; event=" + confirmationId;
        entity.reconciliationEventId = confirmationId; entity.reconciledAt = asOf;
        EvidenceEntity proof = new EvidenceEntity();
        proof.id = UUID.randomUUID(); proof.exceptionId = exceptionId; proof.eventId = confirmationId;
        proof.type = "RECONCILIATION"; proof.source = confirmation.source;
        proof.label = "verifiedCorrectedStock"; proof.value = confirmation.stockAfter.toPlainString();
        proof.occurredAt = confirmation.occurredAt;
        proof.position = evidence.findByExceptionIdOrderByPosition(exceptionId).size();
        evidence.saveAndFlush(proof); exceptions.flush();
    }

    /** Engine-only proof of adoption of an existing confirmed physical checkpoint. */
    @Transactional
    public void recordPhysicalReconciliation(UUID exceptionId, UUID countId, Instant asOf) {
        var entity = exceptions.findByIdForUpdate(exceptionId).orElseThrow();
        if (entity.code != ExceptionCode.E02 || entity.status != ExceptionStatus.OPEN) return;
        var count = events.findById(countId).orElseThrow();
        if (!entity.triggerEventId.equals(countId) || count.type != io.peek.core.events.EventType.PHYSICAL_COUNT
            || !count.confirmed || count.quantity == null || asOf.isBefore(entity.detectedAt))
            throw new ConflictException("Invalid physical reconciliation proof");
        entity.status = ExceptionStatus.RESOLVED; entity.resolvedAt = asOf;
        entity.reconciliationEventId = countId; entity.reconciledAt = asOf;
        entity.resolutionNote = "Approved existing physical checkpoint verified by reconciliation; event=" + countId;
        EvidenceEntity proof = new EvidenceEntity();
        proof.id = UUID.randomUUID(); proof.exceptionId = exceptionId; proof.eventId = countId;
        proof.type = "RECONCILIATION"; proof.source = count.source; proof.label = "verifiedPhysicalCheckpoint";
        proof.value = count.quantity.toPlainString(); proof.occurredAt = asOf;
        proof.position = evidence.findByExceptionIdOrderByPosition(exceptionId).size();
        evidence.saveAndFlush(proof); exceptions.flush();
    }

    private ExceptionEntity require(UUID id) {
        return exceptions.findById(id).orElseThrow(() -> new NotFoundException("Exception not found"));
    }
    private ExceptionView view(ExceptionEntity e) {
        List<EvidenceView> rows = evidence.findByExceptionIdOrderByPosition(e.id).stream()
            .map(row -> new EvidenceView(row.id, row.eventId, row.type, row.source, row.label, row.value, row.occurredAt)).toList();
        return new ExceptionView(e.id, e.code, e.triggerEventId, e.operationCommandId, e.status,
            e.severity, e.title, e.detectedAt,
            e.productId, e.sku, e.orderId, e.expectedState, e.observedState, e.ruleParameter,
            e.impact, e.recommendation, e.resolutionNote, e.resolvedAt, e.version, rows,
            e.reconciliationEventId, e.reconciledAt);
    }
    private static void validate(ExceptionDraft draft) {
        if (draft == null || draft.code() == null || draft.triggerEventId() == null || draft.severity() == null
            || blank(draft.title()) || blank(draft.expectedState()) || blank(draft.observedState())
            || blank(draft.ruleParameter()) || blank(draft.recommendation()) || draft.evidence() == null || draft.evidence().isEmpty()) {
            throw new IllegalArgumentException("Exception requires code, trigger, severity, states, parameter, recommendation and evidence");
        }
        for (EvidenceDraft row : draft.evidence()) {
            if (row == null || blank(row.type()) || blank(row.source()) || blank(row.label()) || blank(row.value())) {
                throw new IllegalArgumentException("Evidence requires type, source, label and value");
            }
        }
    }
    private java.util.Optional<ExceptionEntity> existing(ExceptionDraft draft) {
        return draft.operationCommandId() == null
            ? exceptions.findByCodeAndTriggerEventIdAndOperationCommandIdIsNull(draft.code(), draft.triggerEventId())
            : exceptions.findByCodeAndOperationCommandId(draft.code(), draft.operationCommandId());
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
