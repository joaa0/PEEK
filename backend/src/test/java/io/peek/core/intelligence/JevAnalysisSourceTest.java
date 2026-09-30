package io.peek.core.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.peek.core.exceptions.ExceptionService;
import java.util.List;
import org.junit.jupiter.api.Test;

class JevAnalysisSourceTest {
    private final ExceptionService exceptions = mock(ExceptionService.class);
    private final JevService jev = mock(JevService.class);
    private final JevAnalysisSource source = new JevAnalysisSource(exceptions, jev);

    @Test void compatibilityProjectionKeepsRationaleExistingReferencesAndConfidenceMeaning() {
        var alert = JevFixtures.alert();
        var originalEvidence = List.copyOf(alert.evidence());
        var context = new JevInputFactory().prepare(alert, JevFixtures.events());
        var output = new JevResponseValidator(new ObjectMapper().findAndRegisterModules()).validate(JevFixtures.output(), context);
        var main = output.mainHypothesis();
        var references = main.evidenceIds().stream().map(context.evidenceIds()::get).toList();
        var hypothesis = new JevContract.HypothesisView(main.code(), main.statement(), main.rationale(), main.confidence(), references);
        when(exceptions.get(alert.id())).thenReturn(alert);
        when(jev.forException(alert)).thenReturn(new JevContract.Analysis(JevContract.VERSION, "AVAILABLE", JevContract.NATURE,
            output.summary(), hypothesis, List.of(), output.impact(), output.recommendedAction(), null));
        var projection = source.find(alert.id()).orElseThrow();
        assertTrue(projection.hypothesis().contains(main.statement()));
        assertTrue(projection.hypothesis().contains(main.rationale()));
        assertEquals(references, projection.evidenceIds());
        assertTrue(projection.confidence().contains(JevContract.CONFIDENCE_MEANING));
        assertTrue(projection.confidence().contains("not calibrated probability"));
        assertEquals(originalEvidence, alert.evidence());
    }
    @Test void fallbackAndOtherExceptionsNeverBecomeFabricatedMcpAnalysis() {
        var alert = JevFixtures.alert(); when(exceptions.get(alert.id())).thenReturn(alert);
        when(jev.forException(alert)).thenReturn(new JevContract.Analysis(JevContract.VERSION, "FALLBACK", "NO_MODEL_ANALYSIS",
            "Static explanation", null, List.of(), alert.impact(), alert.recommendation(), "DISABLED"));
        assertTrue(source.find(alert.id()).isEmpty());
        when(jev.forException(alert)).thenReturn(null);
        assertTrue(source.find(alert.id()).isEmpty());
    }
}
