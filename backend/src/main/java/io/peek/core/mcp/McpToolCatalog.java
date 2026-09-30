package io.peek.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Closed tool allowlist and input schemas. No reflective or generic endpoint routing. */
public final class McpToolCatalog {
    private McpToolCatalog() {}
    public record Tool(String name, String description, Map<String, Object> inputSchema, Map<String, Object> annotations) {}
    private static final Map<String, Object> UUID_SCHEMA = Map.of("type", "string", "format", "uuid");
    private static final Map<String, Object> KEY_SCHEMA = Map.of("type", "string", "minLength", 1, "maxLength", 200);
    public static final List<Tool> TOOLS = List.of(
        tool("peek_list_exceptions", "List PEEK exceptions, OPEN by default. Read evidence before choosing an action.",
            Map.of("status", Map.of("type", "string", "enum", List.of("OPEN", "RESOLVED")),
                "code", Map.of("type", "string", "enum", List.of("E01", "E02", "E03", "E04")),
                "offset", Map.of("type", "integer", "minimum", 0),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)), List.of(), true),
        tool("peek_get_exception", "Read evidence, expected/observed state, recommendation, command and retryAllowed.",
            Map.of("exceptionId", UUID_SCHEMA), List.of("exceptionId"), true),
        tool("peek_get_operational_context", "Read existing canonical product, mappings, stock and all inventory/fiscal commands with attempts.",
            Map.of("exceptionId", UUID_SCHEMA), List.of("exceptionId"), true),
        tool(AgentActionService.RETRY_TOOL, "Retry only a failed/timed-out INVENTORY_SYNC associated with OPEN E01. Keep the same key on replay. Acceptance is PENDING_VERIFICATION.",
            Map.of("commandId", UUID_SCHEMA, "idempotencyKey", KEY_SCHEMA),
            List.of("commandId", "idempotencyKey"), false),
        tool(io.peek.core.orchestration.InventoryCorrectionService.TOOL,
            "Correct an eligible target mapping only to the backend-derived targetStock in correctionCandidates. Copy the reviewed decisionFingerprint. Never invent a quantity. Acceptance is PENDING_VERIFICATION; success requires correlated external STOCK_UPDATED and engine proof.",
            Map.of("exceptionId", UUID_SCHEMA, "mappingId", UUID_SCHEMA, "idempotencyKey", KEY_SCHEMA,
                "decisionFingerprint", Map.of("type", "string", "pattern", "^[a-f0-9]{64}$")),
            List.of("exceptionId", "mappingId", "idempotencyKey", "decisionFingerprint"), false),
        tool("peek_get_command_status", "Read attempts, errors, deadlines and external confirmation. Refresh agent audit verification only.",
            Map.of("commandId", UUID_SCHEMA), List.of("commandId"), true),
        tool(io.peek.core.reconciliation.E02ReconciliationService.TOOL,
            "E02 only: accept an EXISTING confirmed physical checkpoint after showing facts, evidence and JEV hypotheses to the human and receiving explicit approval. Never infer human approval. Human approval authorizes an attempt, not resolution. Copy the reviewed decisionFingerprint. Does not adjust external inventory. Acceptance is PENDING_VERIFICATION.",
            Map.of("exceptionId", UUID_SCHEMA, "idempotencyKey", KEY_SCHEMA,
                "humanApproved", Map.of("type", "boolean", "const", true),
                "approvalNote", Map.of("type", "string", "minLength", 1, "maxLength", 1000),
                "decisionFingerprint", Map.of("type", "string", "pattern", "^[a-f0-9]{64}$")),
            List.of("exceptionId", "idempotencyKey", "humanApproved", "approvalNote", "decisionFingerprint"), false),
        tool("peek_get_exception_status", "Read PEEK final state and deterministic reconciliation proof. Success requires VERIFIED. Refresh agent audit verification only.",
            Map.of("exceptionId", UUID_SCHEMA), List.of("exceptionId"), true)
    );

    private static Tool tool(String name, String description, Map<String, Object> properties,
                             List<String> required, boolean readOnly) {
        return new Tool(name, description, Map.of("type", "object", "properties", properties,
            "required", required, "additionalProperties", false),
            Map.of("readOnlyHint", readOnly, "destructiveHint", !readOnly,
                "idempotentHint", true, "openWorldHint", false));
    }

    public static void validate(String name, JsonNode arguments) {
        Tool tool = TOOLS.stream().filter(item -> item.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown tool"));
        if (!arguments.isObject()) throw new IllegalArgumentException("arguments must be an object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
        Set<String> allowed = properties.keySet();
        arguments.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException("Unexpected argument: " + field);
        });
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) tool.inputSchema().get("required");
        for (String field : required)
            if (!arguments.hasNonNull(field)) throw new IllegalArgumentException("Missing argument: " + field);
        arguments.fields().forEachRemaining(field -> {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (key.equals("humanApproved")) {
                if (!value.isBoolean() || !value.asBoolean())
                    throw new IllegalArgumentException("Explicit humanApproved=true is required");
            } else if (key.equals("offset") || key.equals("limit")) {
                if (!value.isIntegralNumber() || !value.canConvertToInt())
                    throw new IllegalArgumentException(key + " must be an integer");
            } else {
                if (!value.isTextual() || value.asText().isBlank())
                    throw new IllegalArgumentException(key + " must be a nonempty string");
                if (key.endsWith("Id") && !UUID.fromString(value.asText()).toString().equalsIgnoreCase(value.asText()))
                    throw new IllegalArgumentException(key + " must be a canonical UUID");
                if (key.equals("idempotencyKey") && value.asText().length() > 200)
                    throw new IllegalArgumentException("idempotencyKey exceeds 200 characters");
                if (key.equals("approvalNote") && value.asText().length() > 1000)
                    throw new IllegalArgumentException("approvalNote exceeds 1000 characters");
                if (key.equals("decisionFingerprint") && !value.asText().matches("[a-f0-9]{64}"))
                    throw new IllegalArgumentException("Invalid decisionFingerprint");
            }
        });
    }
}
