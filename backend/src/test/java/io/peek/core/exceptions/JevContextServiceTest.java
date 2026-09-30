package io.peek.core.exceptions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JevContextServiceTest {
    private ExceptionService.ExceptionView facts() {
        var alert = mock(ExceptionService.ExceptionView.class);
        when(alert.id()).thenReturn(UUID.randomUUID());
        when(alert.recommendation()).thenReturn("Recount and inspect movements");
        when(alert.evidence()).thenReturn(List.of(new ExceptionService.EvidenceView(UUID.randomUUID(), UUID.randomUUID(),
            "PHYSICAL_COUNT", "physical", "physicalCount", "93", null)));
        return alert;
    }
    @Test void absentAnalysisUsesStaticRecommendationWithoutInventingConfidence() {
        var alert = facts(); var value = new JevContextService(List.of()).forException(alert);
        assertEquals("UNAVAILABLE", value.status()); assertNull(value.confidence());
        assertEquals(alert.recommendation(), value.recommendedAction()); assertTrue(value.probableCauses().isEmpty());
    }
    @Test void failingOptionalSourcePreservesDeterministicFallback() {
        var alert = facts();
        var value = new JevContextService(List.of(id -> { throw new IllegalStateException("unavailable"); })).forException(alert);
        assertEquals("UNAVAILABLE", value.status()); assertEquals(alert.recommendation(), value.recommendedAction());
    }
    @Test void existingContractProjectsHypothesesAndFiltersFabricatedEvidenceReferences() {
        var alert = facts(); UUID real = alert.evidence().getFirst().id();
        var analysis = new JevContextService.JevAnalysis("Possible missing movement", "Loss", "0.72",
            List.of(real, UUID.randomUUID()), "Stock may be overstated", "Recount", List.of("Counting error"));
        var value = new JevContextService(List.of(id -> Optional.of(analysis))).forException(alert);
        assertEquals("HYPOTHESIS_NOT_FACT", value.nature()); assertEquals(List.of(real), value.supportingEvidence());
        assertEquals(List.of("Loss", "Counting error"), value.probableCauses()); assertEquals("0.72", value.confidence());
        assertEquals("93", alert.evidence().getFirst().value());
    }
}
