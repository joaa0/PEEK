package io.peek.core.intelligence;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.peek.core.events.EventType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Provider-neutral interpretation of an existing E02; never an event or a rule decision. */
public final class JevContract {
    public static final String VERSION = "1.0";
    public static final String RULE = "abs(physicalStock - expectedStock) > tolerance";
    public static final String NATURE = "HYPOTHESIS_NOT_FACT";
    public static final String CONFIDENCE_MEANING = "MODEL_SELF_REPORTED_RANKING";
    public static final String TYPESAFE_PROBABILITY = "TYPESAFE_CHOICE_PROBABILITY";
    private JevContract() {}

    public record ExpectedState(BigDecimal expectedStock) {}
    public record ObservedState(BigDecimal physicalStock) {}
    public record Configuration(BigDecimal tolerance, String evidenceId) {}
    public record Fact(String id, String kind, EventType eventType, String sourceRef,
                       long secondsBeforeCount, BigDecimal value, boolean confirmed) {}
    public record Calculation(String id, String kind, BigDecimal value) {}
    public record RecentEvent(String eventRef, EventType type, String sourceRef, long secondsBeforeCount,
                              BigDecimal quantity, BigDecimal stockAfter, boolean confirmed) {}
    public record Input(String contractVersion, String exceptionCode, String ruleTriggered,
                        String dataClassification, ExpectedState expectedState, ObservedState observedState,
                        List<Fact> facts, List<Calculation> calculations, Configuration configuration,
                        List<RecentEvent> recentEvents, boolean historyTruncated) {
        public Input {
            facts = List.copyOf(facts); calculations = List.copyOf(calculations);
            recentEvents = List.copyOf(recentEvents);
        }
    }
    public record PreparedContext(Input input, Map<String, UUID> evidenceIds) {
        public PreparedContext { evidenceIds = Map.copyOf(evidenceIds); }
    }
    public enum HypothesisCode {
        COUNT_REQUIRES_VERIFICATION, MOVEMENT_RECORDING_GAP, RECEIPT_RECORDING_GAP,
        ADJUSTMENT_REQUIRES_REVIEW, UNEXPLAINED_DIVERGENCE
    }
    public record ModelConfidence(BigDecimal value, String meaning) {}
    public record Evaluation(String provider, String model, BigDecimal confidence,
                             Map<HypothesisCode, BigDecimal> probabilities, String explanationSource) {
        public Evaluation { probabilities = Map.copyOf(probabilities); }
    }
    public record Hypothesis(HypothesisCode code, String statement, String rationale,
                             ModelConfidence confidence, List<String> evidenceIds) {
        public Hypothesis { evidenceIds = List.copyOf(evidenceIds); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelOutput(String contractVersion, String nature, String summary, Hypothesis mainHypothesis,
                              List<Hypothesis> alternatives, String impact, String recommendedAction, Evaluation evaluation) {
        public ModelOutput { alternatives = List.copyOf(alternatives); }
        public ModelOutput(String contractVersion, String nature, String summary, Hypothesis mainHypothesis,
                           List<Hypothesis> alternatives, String impact, String recommendedAction) {
            this(contractVersion, nature, summary, mainHypothesis, alternatives, impact, recommendedAction, null);
        }
    }
    public record HypothesisView(HypothesisCode code, String statement, String rationale,
                                 ModelConfidence confidence, List<UUID> evidenceIds) {}
    public record Analysis(String contractVersion, String status, String nature, String summary,
                           HypothesisView mainHypothesis, List<HypothesisView> alternatives,
                           String impact, String recommendedAction, String fallbackReason,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Evaluation evaluation) {
        public Analysis(String contractVersion, String status, String nature, String summary,
                        HypothesisView mainHypothesis, List<HypothesisView> alternatives,
                        String impact, String recommendedAction, String fallbackReason) {
            this(contractVersion, status, nature, summary, mainHypothesis, alternatives, impact, recommendedAction, fallbackReason, null);
        }
    }
}
