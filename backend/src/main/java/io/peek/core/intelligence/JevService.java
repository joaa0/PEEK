package io.peek.core.intelligence;

import io.peek.core.events.EventService;
import io.peek.core.events.EventType;
import io.peek.core.events.NormalizedEvent;
import io.peek.core.exceptions.ExceptionCode;
import io.peek.core.exceptions.ExceptionService.ExceptionView;
import io.peek.core.products.ProductService;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/** Query-time enrichment only. No transactions, repositories, writes, commands or rule invocation. */
@Service
public class JevService {
    private final LlmProperties properties;
    private final Environment environment;
    private final EventService events;
    private final ProductService products;
    private final JevInputFactory inputFactory;
    private final JevResponseValidator validator;
    private final LlmClient client;
    private static final Map<EventType, String> DEMO_ADAPTERS = Map.of(
        EventType.STOCK_UPDATED, "mock-inventory", EventType.SALE_CONFIRMED, "mock-sales",
        EventType.PHYSICAL_COUNT, "mock-physical", EventType.GOODS_RECEIVED, "mock-physical",
        EventType.STOCK_ADJUSTED, "mock-physical");

    public JevService(LlmProperties properties, Environment environment, EventService events, ProductService products,
                      JevInputFactory inputFactory, JevResponseValidator validator, LlmClient client) {
        this.properties = properties; this.environment = environment; this.events = events; this.products = products;
        this.inputFactory = inputFactory; this.validator = validator; this.client = client;
    }

    public JevContract.Analysis forException(ExceptionView alert) {
        if (alert.code() != ExceptionCode.E02) return null;
        if (!properties.enabled()) return fallback(alert, "DISABLED");
        if (!properties.syntheticDemo() || !environment.acceptsProfiles(Profiles.of("demo")))
            return fallback(alert, "NON_SYNTHETIC_CONTEXT");
        if (!properties.configured()) return fallback(alert, "INVALID_CONFIGURATION");
        try {
            if (alert.productId() == null) return fallback(alert, "NON_SYNTHETIC_CONTEXT");
            var product = products.get(alert.productId());
            if (!"Demo".equals(product.category()) || !product.sku().equals(alert.sku())
                || !product.sku().matches("(?:CAM|SKU-E02|SKU-E04)-(?:DEMO|QA)-[A-Za-z0-9-]{6,80}"))
                return fallback(alert, "NON_SYNTHETIC_CONTEXT");
            var evidence = alert.evidence().stream().filter(e -> !"RECONCILIATION".equals(e.type())).toList();
            if (evidence.size() > JevInputFactory.MAX_EVIDENCE) return fallback(alert, "INVALID_CONTEXT");
            var referenced = evidence.stream().map(e -> e.eventId()).filter(java.util.Objects::nonNull)
                .distinct().map(events::get).toList();
            if (referenced.isEmpty() || referenced.stream().anyMatch(e -> !synthetic(e)))
                return fallback(alert, "NON_SYNTHETIC_CONTEXT");
            var context = inputFactory.prepare(alert, referenced);
            validator.validateInput(context.input());
            String response;
            try { response = client.interpret(context.input()); }
            catch (LlmClient.Failure error) { return fallback(alert, error.reason().name()); }
            catch (RuntimeException error) { return fallback(alert, "API_FAILURE"); }
            JevContract.ModelOutput output;
            try { output = validator.validate(response, context); }
            catch (RuntimeException error) { return fallback(alert, "INVALID_RESPONSE"); }
            return new JevContract.Analysis(JevContract.VERSION, "AVAILABLE", JevContract.NATURE, output.summary(),
                hypothesis(output.mainHypothesis(), context), output.alternatives().stream().map(h -> hypothesis(h, context)).toList(),
                output.impact(), output.recommendedAction(), null, output.evaluation());
        } catch (RuntimeException error) { return fallback(alert, "INVALID_CONTEXT"); }
    }

    private boolean synthetic(NormalizedEvent event) {
        String adapter = DEMO_ADAPTERS.get(event.type());
        return adapter != null && adapter.equals(event.metadata().get("adapter"));
    }
    private JevContract.HypothesisView hypothesis(JevContract.Hypothesis value, JevContract.PreparedContext context) {
        return new JevContract.HypothesisView(value.code(), value.statement(), value.rationale(), value.confidence(),
            value.evidenceIds().stream().map(context.evidenceIds()::get).toList());
    }
    private JevContract.Analysis fallback(ExceptionView alert, String reason) {
        String summary = "A contagem física registrada (" + alert.observedState() + ") difere do saldo esperado na detecção ("
            + alert.expectedState() + "). A regra aplicou " + alert.ruleParameter() + ". A causa requer investigação.";
        return new JevContract.Analysis(JevContract.VERSION, "FALLBACK", "NO_MODEL_ANALYSIS", summary, null, List.of(),
            alert.impact(), alert.recommendation(), reason);
    }
}
