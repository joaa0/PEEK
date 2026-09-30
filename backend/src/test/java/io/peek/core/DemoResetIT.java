package io.peek.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.peek.core.demo.DemoResetService;
import io.peek.core.events.EventInput;
import io.peek.core.events.EventRepository;
import io.peek.core.events.EventService;
import io.peek.core.integrations.MockPhysicalAdapter;
import io.peek.core.integrations.MockSalesAdapter;
import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.CommandService;
import io.peek.core.presentation.DemoController;
import io.peek.core.products.MappingStatus;
import io.peek.core.products.ProductRepository;
import io.peek.core.products.ProductService;
import io.peek.core.reconciliation.EvaluationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(ProductPropagationIT.TimeConfiguration.class)
class DemoResetIT {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault(
            "PEEK_TEST_DB_URL", "jdbc:postgresql://localhost:5432/peek_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PEEK_TEST_DB_USER", "peek"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault(
            "PEEK_TEST_DB_PASSWORD", "peek_local_only"));
        registry.add("peek.demo.reset-enabled", () -> "true");
    }

    @Autowired TestRestTemplate http;
    @Autowired ProductService products;
    @Autowired ProductRepository productRows;
    @Autowired EventService events;
    @Autowired EventRepository eventRows;
    @Autowired CommandService commands;
    @Autowired EvaluationService evaluator;
    @Autowired JdbcTemplate jdbc;
    @Autowired java.time.Clock clock;
    @Autowired io.peek.core.mcp.AgentActionService agentActions;

    @Test
    void resetIsRepeatableAndDeletesOnlyTheRequestedDemoRun() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String runId = "DEMO-RESET-" + suffix;
        Instant now = clock.instant();

        String controlSku = "CONTROL-" + suffix;
        products.create(new ProductService.ProductInput(
            controlSku, "Non-demo control", null, null, null, "Operational"));
        events.ingest(new EventInput("qa-inventory", "control-" + runId, "STOCK_UPDATED",
            now.minusSeconds(20), null, controlSku, null, null, null, null, null,
            BigDecimal.ZERO, new BigDecimal("10"), null, Map.of()));

        String otherRunId = runId + "1";
        String otherSku = "CAM-" + otherRunId;
        products.create(new ProductService.ProductInput(
            otherSku, "Another demo run", null, null, null, "Demo"));
        events.ingest(new EventInput("qa-inventory", "baseline-" + otherRunId, "STOCK_UPDATED",
            now.minusSeconds(20), null, otherSku, null, null, null, null, null,
            BigDecimal.ZERO, new BigDecimal("10"), null, Map.of()));

        String demoSku = "CAM-" + runId;
        UUID productId = products.create(new ProductService.ProductInput(
            demoSku, "Demo camera", "Fictitious reset fixture", new BigDecimal("120.00"),
            null, "Demo")).value().id();
        products.addMapping(productId, new ProductService.MappingInput(
            "qa-sales", "SALE-CAM-" + runId, MappingStatus.ACTIVE));
        products.addMapping(productId, new ProductService.MappingInput(
            "qa-inventory", "INV-CAM-" + runId, MappingStatus.ACTIVE));
        products.addMapping(productId, new ProductService.MappingInput(
            "qa-fiscal", "FISC-CAM-" + runId, MappingStatus.ACTIVE));
        for (String sku : new String[] {"SKU-E02-" + runId, "SKU-E04-" + runId}) {
            products.create(new ProductService.ProductInput(sku, "Demo scenario", null, null, null, "Demo"));
            events.ingest(new EventInput("qa-inventory", "baseline-" + sku, "STOCK_UPDATED",
                now.minusSeconds(20), null, sku, null, null, null, null, null,
                BigDecimal.ZERO, new BigDecimal("10"), null, Map.of()));
        }

        var saleResponse = http.postForEntity("/api/v1/mock/sales", new MockSalesAdapter.SaleNotice(
            "sale-timeout-" + runId, "qa-sales", "ORDER-" + runId, "SALE-CAM-" + runId,
            BigDecimal.ONE, now.minusSeconds(1)), Map.class);
        assertEquals(HttpStatus.CREATED, saleResponse.getStatusCode());
        UUID saleId = UUID.fromString(saleResponse.getBody().get("id").toString());
        var command = commands.create(CommandKind.INVENTORY_SYNC, new CommandService.CommandInput(
            saleId, "qa-inventory", "sync-" + runId, false)).command();
        assertEquals(1, evaluator.evaluate(command.deadlineAt().plusSeconds(1)).created());
        agentActions.retryInventory(command.id(), "agent-retry-" + runId);

        var receiptResponse = http.postForEntity("/api/v1/mock/physical", new MockPhysicalAdapter.PhysicalNotice(
            "receipt-" + runId, "qa-physical", "RECEIPT", demoSku, null, "RECEIPT-" + runId,
            null, new BigDecimal("2"), null, now), Map.class);
        assertEquals(HttpStatus.CREATED, receiptResponse.getStatusCode());

        var resetResponse = http.postForEntity("/api/v1/demo/reset",
            new DemoController.ResetRequest(runId, "RESET_DEMO"), DemoResetService.ResetResult.class);
        assertEquals(HttpStatus.OK, resetResponse.getStatusCode());
        DemoResetService.ResetResult result = resetResponse.getBody();
        assertNotNull(result);
        assertTrue(result.totalDeleted() > 0);
        assertEquals(3, result.products());
        assertEquals(3, result.mappings());
        assertTrue(result.events() >= 2);
        assertEquals(1, result.commands());
        assertEquals(1, result.exceptions());
        assertEquals(1, result.agentActions());
        assertEquals(2, result.attempts());

        assertFalse(productRows.findBySku(demoSku).isPresent());
        assertFalse(productRows.findBySku("SKU-E02-" + runId).isPresent());
        assertFalse(productRows.findBySku("SKU-E04-" + runId).isPresent());
        assertTrue(productRows.findBySku(controlSku).isPresent());
        assertTrue(productRows.findBySku(otherSku).isPresent());
        assertFalse(eventRows.findBySourceAndExternalEventId("qa-sales", "sale-timeout-" + runId).isPresent());
        assertTrue(eventRows.findBySourceAndExternalEventId("qa-inventory", "control-" + runId).isPresent());
        assertTrue(eventRows.findBySourceAndExternalEventId("qa-inventory", "baseline-" + otherRunId).isPresent());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM demo_configuration WHERE id = 1", Integer.class));

        var replay = http.postForEntity("/api/v1/demo/reset",
            new DemoController.ResetRequest(runId, "RESET_DEMO"), DemoResetService.ResetResult.class);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertNotNull(replay.getBody());
        assertEquals(0, replay.getBody().totalDeleted());
    }

    @Test
    void resetRejectsIdentifiersOutsideTheDemoNamespace() {
        var response = http.postForEntity("/api/v1/demo/reset",
            new DemoController.ResetRequest("customer-data", "RESET_DEMO"), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
