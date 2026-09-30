package io.peek.core.intelligence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import io.peek.core.events.EventType;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class JevResponseValidator {
    private final ObjectMapper json;
    private final JsonSchema inputSchema;
    private final JsonSchema outputSchema;
    private final JsonNode outputDefinition;

    public JevResponseValidator(ObjectMapper json) {
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        inputSchema = factory.getSchema(resource("jev-input.schema.json"), config);
        outputDefinition = resource("jev-output.schema.json");
        outputSchema = factory.getSchema(outputDefinition, config);
    }

    public void validateInput(JevContract.Input input) {
        if (!inputSchema.validate(json.valueToTree(input)).isEmpty())
            throw new IllegalArgumentException("Invalid JEV input schema");
    }

    public JsonNode outputSchema() { return outputDefinition.deepCopy(); }

    public JevContract.ModelOutput validate(String response, JevContract.PreparedContext context) {
        try {
            if (response == null || response.length() > 16_384) throw new IllegalArgumentException("Invalid response size");
            JsonNode node = json.readTree(response);
            if (!outputSchema.validate(node).isEmpty()) throw new IllegalArgumentException("Invalid JEV output schema");
            var result = json.treeToValue(node, JevContract.ModelOutput.class);
            validateSupport(result.mainHypothesis(), context);
            var seen = new HashSet<JevContract.HypothesisCode>();
            seen.add(result.mainHypothesis().code());
            var previous = result.mainHypothesis().confidence().value();
            for (var alternative : result.alternatives()) {
                validateSupport(alternative, context);
                if (!seen.add(alternative.code()) || alternative.code() == JevContract.HypothesisCode.UNEXPLAINED_DIVERGENCE
                    || alternative.confidence().value().compareTo(previous) > 0)
                    throw new IllegalArgumentException("Unranked or generic alternatives");
                previous = alternative.confidence().value();
            }
            validateEvaluation(result, context.input());
            return result;
        } catch (IOException error) {
            // Never include a provider body, token, or potentially sensitive text in errors/logs.
            throw new IllegalArgumentException("Invalid JEV JSON");
        }
    }

    private void validateEvaluation(JevContract.ModelOutput output, JevContract.Input input) {
        var hypotheses = new java.util.ArrayList<JevContract.Hypothesis>();
        hypotheses.add(output.mainHypothesis()); hypotheses.addAll(output.alternatives());
        boolean typed = hypotheses.stream().anyMatch(h -> JevContract.TYPESAFE_PROBABILITY.equals(h.confidence().meaning()));
        if (output.evaluation() == null) {
            if (typed) throw new IllegalArgumentException("Missing TypeSafe evaluation provenance");
            return;
        }
        TypeSafeEvaluation.validateDistribution(output.evaluation().probabilities(), TypeSafeEvaluation.candidates(input).keySet());
        for (var hypothesis : hypotheses) {
            if (!JevContract.TYPESAFE_PROBABILITY.equals(hypothesis.confidence().meaning())
                || hypothesis.confidence().value().compareTo(output.evaluation().probabilities().get(hypothesis.code())) != 0)
                throw new IllegalArgumentException("Inconsistent TypeSafe probabilities");
        }
        if (output.evaluation().probabilities().values().stream()
                .anyMatch(p -> p.compareTo(output.mainHypothesis().confidence().value()) > 0))
            throw new IllegalArgumentException("Invalid TypeSafe choice");
    }

    private void validateSupport(JevContract.Hypothesis hypothesis, JevContract.PreparedContext context) {
        if (!context.evidenceIds().keySet().containsAll(hypothesis.evidenceIds()))
            throw new IllegalArgumentException("Unknown evidence reference");
        EventType required = switch (hypothesis.code()) {
            case COUNT_REQUIRES_VERIFICATION, UNEXPLAINED_DIVERGENCE -> EventType.PHYSICAL_COUNT;
            case MOVEMENT_RECORDING_GAP -> EventType.SALE_CONFIRMED;
            case RECEIPT_RECORDING_GAP -> EventType.GOODS_RECEIVED;
            case ADJUSTMENT_REQUIRES_REVIEW -> EventType.STOCK_ADJUSTED;
        };
        boolean grounded = context.input().facts().stream().anyMatch(fact -> fact.eventType() == required
            && hypothesis.evidenceIds().contains(fact.id()));
        if (!grounded) throw new IllegalArgumentException("Hypothesis lacks contextual factual support");
    }

    private JsonNode resource(String name) {
        try (var input = getClass().getResourceAsStream("/intelligence/" + name)) {
            if (input == null) throw new IllegalStateException("Missing JEV schema");
            return json.readTree(input);
        } catch (IOException error) { throw new IllegalStateException("Unable to load JEV schema"); }
    }
}
