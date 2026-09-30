package io.peek.core.intelligence;

import io.peek.core.events.EventType;
import io.peek.core.events.NormalizedEvent;
import io.peek.core.exceptions.ExceptionCode;
import io.peek.core.exceptions.ExceptionService.ExceptionView;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Allowlisted projection of the persisted detection, not a reconstruction using later events. */
@Component
public class JevInputFactory {
    public static final int MAX_EVIDENCE = 64;
    public static final int MAX_HISTORY = 20;

    public JevContract.PreparedContext prepare(ExceptionView alert, List<NormalizedEvent> events) {
        if (alert.code() != ExceptionCode.E02) throw new IllegalArgumentException("JEV reference case is E02");
        var rows = alert.evidence().stream().filter(e -> !"RECONCILIATION".equals(e.type())).toList();
        if (rows.isEmpty() || rows.size() > MAX_EVIDENCE) throw new IllegalArgumentException("Invalid context size");
        var byId = new LinkedHashMap<UUID, NormalizedEvent>();
        events.forEach(e -> byId.put(e.id(), e));
        var count = byId.get(alert.triggerEventId());
        if (count == null || count.type() != EventType.PHYSICAL_COUNT || !count.confirmed())
            throw new IllegalArgumentException("Existing confirmed count required");
        BigDecimal expected = number(alert.expectedState()), physical = number(alert.observedState());
        if (physical.compareTo(count.quantity()) != 0) throw new IllegalArgumentException("Count evidence changed");
        var facts = new ArrayList<JevContract.Fact>();
        var calculations = new ArrayList<JevContract.Calculation>();
        var references = new LinkedHashMap<String, UUID>();
        var sourceAliases = new LinkedHashMap<String, String>();
        JevContract.Configuration configuration = null;
        boolean foundCount = false, foundExpected = false, foundDelta = false;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            String alias = "e" + (index + 1);
            references.put(alias, row.id());
            BigDecimal value = number(row.value());
            switch (row.type()) {
                case "PHYSICAL_COUNT", "BASELINE", "CHECKPOINT", "MOVEMENT" -> {
                    var event = byId.get(row.eventId());
                    if (event == null || !java.util.Objects.equals(alert.sku(), event.sku())
                        || event.occurredAt().isAfter(count.occurredAt()))
                        throw new IllegalArgumentException("Unrelated evidence event");
                    BigDecimal factual = event.type() == EventType.STOCK_UPDATED ? event.stockAfter() : event.quantity();
                    if (factual == null || value.compareTo(factual) != 0)
                        throw new IllegalArgumentException("Evidence value changed");
                    if ("PHYSICAL_COUNT".equals(row.type()) && row.eventId().equals(count.id())) foundCount = true;
                    String source = sourceAliases.computeIfAbsent(event.source(), key -> "source" + (sourceAliases.size() + 1));
                    facts.add(new JevContract.Fact(alias, row.type(), event.type(), source,
                        Duration.between(event.occurredAt(), count.occurredAt()).getSeconds(), value, event.confirmed()));
                }
                case "CALCULATION" -> {
                    if ("expectedStock".equals(row.label()) && value.compareTo(expected) == 0 && !foundExpected) {
                        foundExpected = true;
                    } else if ("delta".equals(row.label()) && value.compareTo(physical.subtract(expected)) == 0 && !foundDelta) {
                        foundDelta = true;
                    } else throw new IllegalArgumentException("Unsupported or inconsistent calculation");
                    calculations.add(new JevContract.Calculation(alias, row.label(), value));
                }
                case "PARAMETER" -> {
                    if (!"tolerance".equals(row.label()) || configuration != null || value.signum() < 0
                        || !alert.ruleParameter().equals("tolerance=" + value.stripTrailingZeros().toPlainString()))
                        throw new IllegalArgumentException("Unsupported parameter");
                    configuration = new JevContract.Configuration(value, alias);
                }
                default -> throw new IllegalArgumentException("Unsupported evidence kind");
            }
        }
        if (!foundCount || !foundExpected || !foundDelta || configuration == null)
            throw new IllegalArgumentException("Incomplete deterministic detection evidence");
        var related = rows.stream().map(e -> byId.get(e.eventId())).filter(java.util.Objects::nonNull).distinct()
            .sorted(Comparator.comparing(NormalizedEvent::occurredAt)
                .thenComparing(NormalizedEvent::receivedAt).thenComparing(NormalizedEvent::id)).toList();
        var history = new ArrayList<JevContract.RecentEvent>();
        int start = Math.max(0, related.size() - MAX_HISTORY);
        for (int index = start; index < related.size(); index++) {
            var event = related.get(index);
            history.add(new JevContract.RecentEvent("event" + (index + 1), event.type(), sourceAliases.get(event.source()),
                Duration.between(event.occurredAt(), count.occurredAt()).getSeconds(), event.quantity(), event.stockAfter(), event.confirmed()));
        }
        return new JevContract.PreparedContext(new JevContract.Input(JevContract.VERSION, "E02", JevContract.RULE,
            "SYNTHETIC_DEMO", new JevContract.ExpectedState(expected), new JevContract.ObservedState(physical),
            facts, calculations, configuration, history, start > 0), references);
    }

    private BigDecimal number(String value) {
        if (value == null || !value.matches("-?\\d{1,15}(?:\\.\\d{1,3})?"))
            throw new IllegalArgumentException("Only bounded numeric evidence is allowed");
        return new BigDecimal(value);
    }
}
