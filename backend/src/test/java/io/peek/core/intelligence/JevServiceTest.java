package io.peek.core.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.peek.core.events.EventService;
import io.peek.core.exceptions.ExceptionCode;
import io.peek.core.products.ProductService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JevServiceTest {
    private final EventService events = mock(EventService.class);
    private final ProductService products = mock(ProductService.class);
    private final LlmClient client = mock(LlmClient.class);
    private JevService service(boolean enabled, boolean synthetic, boolean demo) {
        var environment = new MockEnvironment(); if (demo) environment.setActiveProfiles("demo");
        var product = mock(ProductService.ProductView.class);
        when(product.sku()).thenReturn(JevFixtures.SKU); when(product.category()).thenReturn("Demo");
        when(products.get(JevFixtures.PRODUCT)).thenReturn(product);
        JevFixtures.events().forEach(e -> when(events.get(e.id())).thenReturn(e));
        var properties = new LlmProperties(enabled, synthetic, "http://127.0.0.1:9999/chat/completions", "test-model", "",
            Duration.ofSeconds(1), Duration.ofMillis(100));
        return new JevService(properties, environment, events, products, new JevInputFactory(),
            new JevResponseValidator(new ObjectMapper().findAndRegisterModules()), client);
    }
    @Test void successReturnsUiReadyGroundedHypothesisAndNeverChangesOriginalEvidence() {
        var alert = JevFixtures.alert(); var original = java.util.List.copyOf(alert.evidence());
        var service = service(true, true, true); when(client.interpret(any())).thenReturn(JevFixtures.output());
        var value = service.forException(alert);
        assertEquals("AVAILABLE", value.status()); assertNull(value.fallbackReason());
        assertEquals(original, alert.evidence()); assertEquals("95", alert.expectedState());
        assertEquals(alert.evidence().getFirst().id(), value.mainHypothesis().evidenceIds().getFirst());
        verify(client).interpret(any());
    }
    @Test void noLlmUsesDeterministicFallbackWithoutFetchingContextOrInventingConfidence() {
        var value = service(false, true, true).forException(JevFixtures.alert());
        assertEquals("DISABLED", value.fallbackReason()); assertNull(value.mainHypothesis());
        assertTrue(value.summary().contains("93")); assertTrue(value.summary().contains("95"));
        verifyNoInteractions(client, events);
    }
    @Test void realModeAndMissingExplicitSyntheticFlagNeverSendAnything() {
        assertEquals("NON_SYNTHETIC_CONTEXT", service(true, true, false).forException(JevFixtures.alert()).fallbackReason());
        assertEquals("NON_SYNTHETIC_CONTEXT", service(true, false, true).forException(JevFixtures.alert()).fallbackReason());
        verifyNoInteractions(client, events);
    }
    @Test void nonDemoProductAndNonAdapterEventsNeverReachTheLlm() {
        var service = service(true, true, true);
        var product = products.get(JevFixtures.PRODUCT); when(product.category()).thenReturn("Operational");
        assertEquals("NON_SYNTHETIC_CONTEXT", service.forException(JevFixtures.alert()).fallbackReason());
        when(product.category()).thenReturn("Demo");
        var event = mock(io.peek.core.events.NormalizedEvent.class);
        when(event.type()).thenReturn(io.peek.core.events.EventType.STOCK_UPDATED); when(event.metadata()).thenReturn(java.util.Map.of());
        when(events.get(JevFixtures.BASELINE)).thenReturn(event);
        assertEquals("NON_SYNTHETIC_CONTEXT", service.forException(JevFixtures.alert()).fallbackReason()); verifyNoInteractions(client);
    }
    @Test void invalidResponseAndFabricatedEventsKeepStaticRecommendationAndFacts() {
        var service = service(true, true, true); var alert = JevFixtures.alert();
        for (String invalid : new String[] {"broken", "{}", JevFixtures.output().replace("e1", "e99"),
                JevFixtures.output().replace("\"alternatives\":[]", "\"events\":[],\"alternatives\":[]")}) {
            when(client.interpret(any())).thenReturn(invalid);
            var value = service.forException(alert); assertEquals("INVALID_RESPONSE", value.fallbackReason());
            assertEquals("FALLBACK", value.status()); assertEquals(alert.recommendation(), value.recommendedAction());
            assertNull(value.mainHypothesis()); assertEquals(6, alert.evidence().size());
        }
    }
    @Test void timeoutFailureAndUnexpectedProviderExceptionAllFallBack() {
        var service = service(true, true, true); var alert = JevFixtures.alert();
        for (var reason : LlmClient.FailureReason.values()) {
            doThrow(new LlmClient.Failure(reason)).when(client).interpret(any());
            assertEquals(reason.name(), service.forException(alert).fallbackReason());
        }
        doThrow(new IllegalStateException("synthetic-private-message")).when(client).interpret(any());
        var value = service.forException(alert); assertEquals("API_FAILURE", value.fallbackReason());
        assertFalse(value.toString().contains("synthetic-private-message"));
    }
    @Test void incompleteContextCannotSuppressExistingException() {
        var service = service(true, true, true); when(events.get(JevFixtures.COUNT)).thenThrow(new IllegalArgumentException("absent"));
        assertEquals("INVALID_CONTEXT", service.forException(JevFixtures.alert()).fallbackReason()); verifyNoInteractions(client);
    }
    @Test void otherExceptionFamiliesDoNotCallLlm() {
        var alert = mock(io.peek.core.exceptions.ExceptionService.ExceptionView.class);
        var service = service(true, true, true);
        for (var code : new ExceptionCode[] {ExceptionCode.E01, ExceptionCode.E03, ExceptionCode.E04}) {
            when(alert.code()).thenReturn(code); assertNull(service.forException(alert));
        }
        verifyNoInteractions(client, events);
    }
}
