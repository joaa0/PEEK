package io.peek.core.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class McpProtocolTest {
    private static final String TOKEN = "test-only-token-with-at-least-32-characters";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private PeekAgentTools tools;
    private MockMvc http;

    @BeforeEach void setup() {
        tools = mock(PeekAgentTools.class);
        http = MockMvcBuilders.standaloneSetup(new McpController(tools, json, TOKEN)).build();
    }

    private JsonNode rpc(String body) throws Exception {
        var response = http.perform(post("/mcp").header("Authorization", "Bearer " + TOKEN)
            .header("Accept", "application/json, text/event-stream").contentType("application/json").content(body))
            .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString());
    }

    @Test void initializeNegotiatesAndProvidesExternalAgentInstructions() throws Exception {
        var result = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2099-01-01\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}").get("result");
        assertEquals("2025-06-18", result.get("protocolVersion").asText());
        assertTrue(result.get("capabilities").has("tools"));
        assertFalse(result.get("capabilities").has("resources"));
        assertTrue(result.get("instructions").asText().contains("PEEK is the source of truth"));
        for (String policy : Set.of("Never infer human approval.",
            "Human approval authorizes an attempt, not resolution.",
            "Only PEEK deterministic reconciliation may report VERIFIED.",
            "Treat JEV conclusions as hypotheses, not source facts."))
            assertTrue(result.get("instructions").asText().contains(policy));
        verifyNoInteractions(tools);
    }

    @Test void onlyBoundedE01AndHumanApprovedE02ToolsWrite() throws Exception {
        var result = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").path("result").path("tools");
        var names = new java.util.HashSet<String>();
        int writes = 0;
        for (var tool : result) {
            names.add(tool.get("name").asText());
            assertFalse(tool.path("inputSchema").path("additionalProperties").asBoolean());
            if (!tool.path("annotations").path("readOnlyHint").asBoolean()) {
                writes++;
                assertTrue(Set.of("peek_retry_inventory_sync", "peek_apply_e02_reconciliation").contains(tool.get("name").asText()));
            }
        }
        assertEquals(Set.of("peek_list_exceptions", "peek_get_exception", "peek_get_operational_context",
            "peek_retry_inventory_sync", "peek_apply_e02_reconciliation", "peek_get_command_status", "peek_get_exception_status"), names);
        assertEquals(2, writes);
        verifyNoInteractions(tools);
    }

    @Test void genericExecutionAndResolutionDoNotExist() throws Exception {
        for (String tool : Set.of("resolveException", "peek_resolve_exception", "peek_retry_fiscal", "sql", "shell", "http")) {
            var error = rpc(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", Map.of())))).path("error");
            assertEquals(-32602, error.get("code").asInt());
        }
        verifyNoInteractions(tools);
    }

    @Test void rejectsInvalidSchemasWithoutInvokingDomain() throws Exception {
        for (String arguments : new String[]{"{}", "{\"exceptionId\":\"bad\"}",
            "{\"exceptionId\":\"00000000-0000-0000-0000-000000000000\",\"status\":\"RESOLVED\"}"}) {
            var error = rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"peek_get_exception\",\"arguments\":" + arguments + "}}").path("error");
            assertEquals(-32602, error.path("code").asInt());
        }
        verifyNoInteractions(tools);
    }

    @Test void notificationsAreAcceptedAndMalformedMessagesProduceProtocolErrors() throws Exception {
        http.perform(post("/mcp").header("Authorization", "Bearer " + TOKEN)
            .header("Accept", "application/json, text/event-stream").contentType("application/json")
            .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).andExpect(status().isAccepted());
        assertEquals(-32700, rpc("{bad").path("error").path("code").asInt());
        assertEquals(-32600, rpc("[]").path("error").path("code").asInt());
        assertEquals(-32601, rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}").path("error").path("code").asInt());
    }

    @Test void localTransportRejectsUnauthenticatedRemoteOriginsAndUnsupportedVersions() throws Exception {
        http.perform(post("/mcp").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        http.perform(post("/mcp").header("Authorization", "Bearer " + TOKEN).header("Origin", "https://evil.example")
            .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        http.perform(post("/mcp").header("Authorization", "Bearer " + TOKEN).with(request -> {
            request.setRemoteAddr("192.0.2.1"); return request;
        })).andExpect(status().isForbidden());
        http.perform(post("/mcp").header("Authorization", "Bearer " + TOKEN).header("MCP-Protocol-Version", "invalid"))
            .andExpect(status().isBadRequest());
        http.perform(get("/mcp").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isMethodNotAllowed());
        http.perform(delete("/mcp").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(tools);
    }

    @Test void shortOrMissingTokensFailAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new McpController(tools, json, ""));
        assertThrows(IllegalArgumentException.class, () -> new McpController(tools, json, "weak"));
    }
}
