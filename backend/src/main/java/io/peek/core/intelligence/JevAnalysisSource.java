package io.peek.core.intelligence;

import io.peek.core.exceptions.ExceptionService;
import io.peek.core.exceptions.JevContextService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Backwards-compatible read projection for the existing MCP interpretation contract. */
@Component
public class JevAnalysisSource implements JevContextService.AnalysisSource {
    private final ExceptionService exceptions;
    private final JevService jev;
    public JevAnalysisSource(ExceptionService exceptions, JevService jev) { this.exceptions = exceptions; this.jev = jev; }
    @Override public Optional<JevContextService.JevAnalysis> find(UUID exceptionId) {
        var value = jev.forException(exceptions.get(exceptionId));
        if (value == null || !"AVAILABLE".equals(value.status())) return Optional.empty();
        var primary = value.mainHypothesis();
        return Optional.of(new JevContextService.JevAnalysis(value.summary(), primary.statement() + " — " + primary.rationale(),
            primary.confidence().value().toPlainString() + " (" + primary.confidence().meaning()
                + (JevContract.TYPESAFE_PROBABILITY.equals(primary.confidence().meaning())
                    ? "; probability among proposed hypotheses, not proof of cause)"
                    : "; not calibrated probability)"),
            primary.evidenceIds(), value.impact(), value.recommendedAction(),
            value.alternatives().stream().map(h -> h.statement() + " — " + h.rationale()).toList()));
    }
}
