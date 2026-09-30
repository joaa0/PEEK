package io.peek.core.exceptions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Optional projection of the existing UI JEV contract. No model calls or invented analysis. */
@Service
public class JevContextService {
    // Mirrors frontend JevAnalysis; a future existing analysis store can supply this projection.
    public record JevAnalysis(String summary, String hypothesis, String confidence, List<UUID> evidenceIds,
        String impact, String recommendedAction, List<String> alternatives) {}
    public interface AnalysisSource { Optional<JevAnalysis> find(UUID exceptionId); }
    public record Interpretation(String status, String nature, String explanation, List<String> probableCauses,
        List<UUID> supportingEvidence, String operationalImpact, String recommendedAction, String confidence) {}
    private final List<AnalysisSource> sources;
    public JevContextService(List<AnalysisSource> sources) { this.sources = List.copyOf(sources); }

    public Interpretation forException(ExceptionService.ExceptionView exception) {
        for (var source : sources) {
            try {
                var available = source.find(exception.id());
                if (available.isEmpty()) continue;
                var analysis = available.get();
                var factualIds = exception.evidence().stream().map(ExceptionService.EvidenceView::id).toList();
                var support = analysis.evidenceIds() == null ? List.<UUID>of()
                    : analysis.evidenceIds().stream().filter(factualIds::contains).toList();
                var hypotheses = new java.util.ArrayList<String>();
                if (analysis.hypothesis() != null) hypotheses.add(analysis.hypothesis());
                if (analysis.alternatives() != null) hypotheses.addAll(analysis.alternatives());
                return new Interpretation("AVAILABLE", "HYPOTHESIS_NOT_FACT", analysis.summary(), List.copyOf(hypotheses),
                    support, analysis.impact(), analysis.recommendedAction(), analysis.confidence());
            } catch (RuntimeException unavailable) {
                // Interpretation failure must not suppress factual context or deterministic actions.
            }
        }
        return new Interpretation("UNAVAILABLE", "NO_MODEL_ANALYSIS", null, List.of(), List.of(),
            null, exception.recommendation(), null);
    }
}
