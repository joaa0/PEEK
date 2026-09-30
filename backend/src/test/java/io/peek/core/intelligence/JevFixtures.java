package io.peek.core.intelligence;

import io.peek.core.events.EventType;
import io.peek.core.events.NormalizedEvent;
import io.peek.core.exceptions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JevFixtures {
    public static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    public static final String SKU = "SKU-E02-DEMO-JEV-123456";
    public static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID BASELINE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID SALE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID COUNT = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private JevFixtures() {}

    public static List<NormalizedEvent> events() {
        return List.of(event(BASELINE, EventType.STOCK_UPDATED, null, new BigDecimal("100"), 30, "mock-inventory"),
            event(SALE, EventType.SALE_CONFIRMED, new BigDecimal("5"), null, 20, "mock-sales"),
            event(COUNT, EventType.PHYSICAL_COUNT, new BigDecimal("93"), null, 10, "mock-physical"));
    }
    private static NormalizedEvent event(UUID id, EventType type, BigDecimal quantity, BigDecimal stock, int seconds, String adapter) {
        return new NormalizedEvent(id, "fictitious-source", "synthetic-external-id", type,
            NOW.minusSeconds(seconds), NOW, PRODUCT, SKU, "synthetic-item", "synthetic-order", null, null, null,
            quantity, stock, type == EventType.PHYSICAL_COUNT,
            Map.of("adapter", adapter, "sourceReference", "synthetic-reference", "privateText", "MUST_NOT_LEAVE_PEEK"));
    }
    public static ExceptionService.ExceptionView alert() {
        var evidence = List.of(row(COUNT, "PHYSICAL_COUNT", "physicalCount", "93"),
            row(null, "CALCULATION", "expectedStock", "95"), row(null, "CALCULATION", "delta", "-2"),
            row(null, "PARAMETER", "tolerance", "1"), row(BASELINE, "BASELINE", "stockAfter", "100"),
            row(SALE, "MOVEMENT", "SALE_CONFIRMED", "5"));
        return new ExceptionService.ExceptionView(UUID.randomUUID(), ExceptionCode.E02, COUNT, null, ExceptionStatus.OPEN,
            Severity.WARNING, "Physical divergence", NOW, PRODUCT, SKU, null, "95", "93", "tolerance=1", "Inaccurate balance",
            "Recount the item and reconcile movements before adjusting stock", null, null, 0, evidence, null, null);
    }
    private static ExceptionService.EvidenceView row(UUID event, String type, String label, String value) {
        return new ExceptionService.EvidenceView(UUID.randomUUID(), event, type, "fictitious-source", label, value, NOW.minusSeconds(10));
    }
    public static String output() {
        return """
            {"contractVersion":"1.0","nature":"HYPOTHESIS_NOT_FACT",
             "summary":"A contagem requer verificação; a causa permanece uma hipótese.",
             "mainHypothesis":{"code":"COUNT_REQUIRES_VERIFICATION","statement":"A contagem pode estar incompleta.",
               "rationale":"A contagem e o delta documentam divergência; não há prova da causa.",
               "confidence":{"value":0.6,"meaning":"MODEL_SELF_REPORTED_RANKING"},"evidenceIds":["e1","e3"]},
             "alternatives":[],"impact":"O saldo pode estar superestimado.",
             "recommendedAction":"Recontar e conferir movimentos antes de qualquer ajuste."}
            """;
    }
    public static String typeSafeResponse() {
        return """
            {"model":"jev-test","answers":{"hypothesis":{"type":"choice","choice":"COUNT_REQUIRES_VERIFICATION",
             "probabilities":{"COUNT_REQUIRES_VERIFICATION":0.6,"MOVEMENT_RECORDING_GAP":0.1,"UNEXPLAINED_DIVERGENCE":0.3},
             "confidence":0.4}},"usage":{"input_tokens":300,"output_tokens":20}}
            """;
    }
}
