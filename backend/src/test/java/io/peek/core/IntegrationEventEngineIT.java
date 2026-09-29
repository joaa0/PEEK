package io.peek.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.peek.core.events.EventInput;
import io.peek.core.events.EventService;
import io.peek.core.integrations.MockFiscalAdapter;
import io.peek.core.integrations.MockInventoryAdapter;
import io.peek.core.integrations.MockPhysicalAdapter;
import io.peek.core.integrations.MockSalesAdapter;
import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.CommandService;
import io.peek.core.orchestration.CommandStatus;
import io.peek.core.products.MappingStatus;
import io.peek.core.products.ProductService;
import io.peek.core.reconciliation.EvaluationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationEventEngineIT {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("PEEK_TEST_DB_URL", "jdbc:postgresql://localhost:5432/peek_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PEEK_TEST_DB_USER", "peek"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("PEEK_TEST_DB_PASSWORD", "peek_local_only"));
    }
    @Autowired TestRestTemplate http;
    @Autowired ProductService products;
    @Autowired EventService events;
    @Autowired CommandService commands;
    @Autowired EvaluationService evaluator;
    @Autowired JdbcTemplate jdbc;

    private record Fixture(UUID productId, String sku, String salesChannel, String salesItem,
                           String inventoryChannel, String inventoryItem, String fiscalChannel, String fiscalItem) {}
    private Fixture fixture() {
        String id = UUID.randomUUID().toString();
        String sku = "SKU-" + id;
        UUID productId = products.create(new ProductService.ProductInput(sku, "Fictitious", null, null, null, null)).value().id();
        String sales = "sales-" + id, inventory = "inventory-" + id, fiscal = "fiscal-" + id;
        String salesItem = "SALE-" + id, inventoryItem = "STOCK-" + id, fiscalItem = "DOC-" + id;
        products.addMapping(productId, new ProductService.MappingInput(sales, salesItem, MappingStatus.ACTIVE));
        products.addMapping(productId, new ProductService.MappingInput(inventory, inventoryItem, MappingStatus.ACTIVE));
        products.addMapping(productId, new ProductService.MappingInput(fiscal, fiscalItem, MappingStatus.ACTIVE));
        return new Fixture(productId, sku, sales, salesItem, inventory, inventoryItem, fiscal, fiscalItem);
    }

    @Test void adaptersAndInventoryCommandRequireExactExternalConfirmationAndRemainIdempotent() {
        Fixture f = fixture();
        Instant now = Instant.now();
        base(f, now.minusSeconds(10));
        var saleNotice = new MockSalesAdapter.SaleNotice("S-" + UUID.randomUUID(), f.salesChannel(), "O1", f.salesItem(),
            new BigDecimal("5"), now.minusSeconds(2));
        var saleResponse = http.postForEntity("/api/v1/mock/sales", saleNotice, Map.class);
        assertEquals(HttpStatus.CREATED, saleResponse.getStatusCode());
        assertEquals(HttpStatus.OK, http.postForEntity("/api/v1/mock/sales", saleNotice, Map.class).getStatusCode());
        UUID saleId = UUID.fromString(saleResponse.getBody().get("id").toString());
        var input = new CommandService.CommandInput(saleId, f.inventoryChannel(), "sync-" + saleId, false);
        var command = commands.create(CommandKind.INVENTORY_SYNC, input);
        assertTrue(command.created());
        assertEquals(CommandStatus.PENDING_CONFIRMATION, command.command().status());
        assertEquals(1, command.command().attempts().size());
        Map<?, ?> pendingContext = http.getForObject("/api/v1/products/" + f.productId() + "/context", Map.class);
        Map<?, ?> pendingExecution = (Map<?, ?>) pendingContext.get("inventorySync");
        assertEquals("PENDING_CONFIRMATION", pendingExecution.get("status"));
        assertEquals(command.command().id().toString(), pendingExecution.get("commandId"));
        assertEquals(f.inventoryChannel(), pendingExecution.get("channel"));
        assertNotNull(pendingExecution.get("requestedAt"));
        assertNotNull(pendingExecution.get("deadlineAt"));
        assertFalse(commands.create(CommandKind.INVENTORY_SYNC, input).created());
        assertEquals(HttpStatus.CONFLICT, http.postForEntity("/api/v1/commands/inventory-sync",
            new CommandService.CommandInput(saleId, f.inventoryChannel(), "another-" + saleId, false), Map.class).getStatusCode());

        Instant confirmationAt = command.command().requestedAt().plusSeconds(1);
        var wrongOrder = new MockInventoryAdapter.StockNotice("W-" + UUID.randomUUID(), f.inventoryChannel(),
            f.inventoryItem(), "WRONG", null, new BigDecimal("5"), new BigDecimal("95"), confirmationAt);
        assertEquals(HttpStatus.CREATED, http.postForEntity("/api/v1/mock/inventory", wrongOrder, Map.class).getStatusCode());
        assertEquals(CommandStatus.PENDING_CONFIRMATION, commands.get(command.command().id()).status());
        var confirmed = new MockInventoryAdapter.StockNotice("C-" + UUID.randomUUID(), f.inventoryChannel(),
            f.inventoryItem(), "O1", null, new BigDecimal("5"), new BigDecimal("95"), confirmationAt);
        assertEquals(HttpStatus.CREATED, http.postForEntity("/api/v1/mock/inventory", confirmed, Map.class).getStatusCode());
        assertEquals(CommandStatus.CONFIRMED, commands.get(command.command().id()).status());
        assertNotNull(commands.get(command.command().id()).confirmationEventId());
        Map<?, ?> confirmedContext = http.getForObject("/api/v1/products/" + f.productId() + "/context", Map.class);
        assertEquals("CONFIRMED", ((Map<?, ?>) confirmedContext.get("inventorySync")).get("status"));
        evaluator.evaluate(command.command().deadlineAt().plusSeconds(1));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM operational_exception WHERE code = 'E01' AND operation_command_id = ?",
            Integer.class, command.command().id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operation_attempt WHERE command_id = ?", Integer.class, command.command().id()));
    }

    @Test void inventoryTimeoutAndRetryKeepAttemptsAndEvidence() {
        Fixture f = fixture();
        Instant now = Instant.now();
        var sale = events.ingest(new EventInput(f.salesChannel(), "S-" + UUID.randomUUID(), "SALE_CONFIRMED",
            now.minusSeconds(1), null, null, f.salesItem(), "O2", null, null, null, BigDecimal.ONE,
            null, null, Map.of())).event();
        var command = commands.create(CommandKind.INVENTORY_SYNC,
            new CommandService.CommandInput(sale.id(), f.inventoryChannel(), "sync-" + sale.id(), true)).command();
        assertEquals(CommandStatus.FAILED, command.status());
        assertEquals("MOCK_INVENTORY_DISPATCH_FAILURE", command.attempts().get(0).errorCode());
        Map<?, ?> failedContext = http.getForObject("/api/v1/products/" + f.productId() + "/context", Map.class);
        assertEquals("FAILED", ((Map<?, ?>) failedContext.get("inventorySync")).get("status"));
        assertEquals(0, evaluator.evaluate(command.deadlineAt().minusNanos(1)).created());
        var result = evaluator.evaluate(command.deadlineAt().plusSeconds(1));
        assertEquals(1, result.created());
        Map<?, ?> exception = http.getForObject("/api/v1/exceptions/" + result.exceptionIds().get(0), Map.class);
        List<?> evidence = (List<?>) exception.get("evidence");
        assertTrue(evidence.stream().map(row -> ((Map<?, ?>) row).get("label"))
            .anyMatch("mappingId"::equals));
        assertTrue(evidence.stream().map(row -> ((Map<?, ?>) row).get("value"))
            .anyMatch("MOCK_INVENTORY_DISPATCH_FAILURE"::equals));
        assertEquals(0, evaluator.evaluate(command.deadlineAt().plusSeconds(1)).created());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_exception WHERE code = 'E01' AND trigger_event_id = ?", Integer.class, sale.id()));
        var retried = commands.retry(command.id(), "retry-1", false);
        assertEquals(2, retried.attempts().size());
        assertEquals(CommandStatus.PENDING_CONFIRMATION, retried.status());
        var replay = http.postForEntity("/api/v1/commands/" + command.id() + "/retry",
            Map.of("idempotencyKey", "retry-1", "simulateFailure", false), Map.class);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertEquals(2, ((List<?>) replay.getBody().get("attempts")).size());
        assertEquals(HttpStatus.CONFLICT, http.postForEntity("/api/v1/commands/" + command.id() + "/retry",
            Map.of("idempotencyKey", "retry-2", "simulateFailure", false), Map.class).getStatusCode());
    }

    @Test void concurrentRetryWithSameKeyCreatesOneAuditableAttempt() throws Exception {
        Fixture f = fixture();
        Instant now = Instant.now();
        var sale = events.ingest(new EventInput(f.salesChannel(), "S-" + UUID.randomUUID(), "SALE_CONFIRMED",
            now.minusSeconds(1), null, null, f.salesItem(), "O-CONCURRENT", null, null, null,
            BigDecimal.ONE, null, null, Map.of())).event();
        var command = commands.create(CommandKind.INVENTORY_SYNC,
            new CommandService.CommandInput(sale.id(), f.inventoryChannel(), "sync-" + sale.id(), true)).command();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return http.postForEntity("/api/v1/commands/" + command.id() + "/retry",
                    Map.of("idempotencyKey", "concurrent-retry", "simulateFailure", true), Map.class);
            });
            var second = executor.submit(() -> {
                start.await();
                return http.postForEntity("/api/v1/commands/" + command.id() + "/retry",
                    Map.of("idempotencyKey", "concurrent-retry", "simulateFailure", true), Map.class);
            });
            start.countDown();
            assertEquals(HttpStatus.OK, first.get().getStatusCode());
            assertEquals(HttpStatus.OK, second.get().getStatusCode());
            assertEquals(2, commands.get(command.id()).attempts().size());
            assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM operation_attempt WHERE command_id = ? AND idempotency_key = ?",
                Integer.class, command.id(), "concurrent-retry"));
            var accepted = commands.retry(command.id(), "recovery-after-concurrent-retry", false);
            assertEquals(CommandStatus.PENDING_CONFIRMATION, accepted.status());
            var confirmation = new MockInventoryAdapter.StockNotice("C-" + UUID.randomUUID(),
                f.inventoryChannel(), f.inventoryItem(), "O-CONCURRENT", null, BigDecimal.ONE,
                new BigDecimal("99"), accepted.requestedAt().plusSeconds(1));
            assertEquals(HttpStatus.CREATED,
                http.postForEntity("/api/v1/mock/inventory", confirmation, Map.class).getStatusCode());
            assertEquals(CommandStatus.CONFIRMED, commands.get(command.id()).status());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void physicalCountAndReceiptUseBaselineToleranceAndReceiptCorrelation() {
        Fixture f = fixture();
        Instant at = Instant.now().minusSeconds(100);
        base(f, at);
        var receipt = new MockPhysicalAdapter.PhysicalNotice("R-" + UUID.randomUUID(), "physical", "RECEIPT",
            f.sku(), null, "R1", null, new BigDecimal("10"), null, at.plusSeconds(1));
        assertEquals(HttpStatus.CREATED, http.postForEntity("/api/v1/mock/physical", receipt, Map.class).getStatusCode());
        var unrelated = new MockInventoryAdapter.StockNotice("X-" + UUID.randomUUID(), f.inventoryChannel(),
            f.inventoryItem(), null, "R2", new BigDecimal("10"), new BigDecimal("110"), at.plusSeconds(2));
        assertEquals(HttpStatus.CREATED, http.postForEntity("/api/v1/mock/inventory", unrelated, Map.class).getStatusCode());
        assertEquals(0, evaluator.evaluate(at.plusSeconds(3)).created());
        var registration = new MockInventoryAdapter.StockNotice("Y-" + UUID.randomUUID(), f.inventoryChannel(),
            f.inventoryItem(), null, "R1", new BigDecimal("7"), new BigDecimal("107"), at.plusSeconds(4));
        http.postForEntity("/api/v1/mock/inventory", registration, Map.class);
        var count = new MockPhysicalAdapter.PhysicalNotice("COUNT-" + UUID.randomUUID(), "physical", "COUNT",
            f.sku(), null, null, null, new BigDecimal("106"), true, at.plusSeconds(5));
        http.postForEntity("/api/v1/mock/physical", count, Map.class);
        var evaluation = evaluator.evaluate(at.plusSeconds(6));
        assertEquals(2, evaluation.created());
        assertEquals(0, evaluator.evaluate(at.plusSeconds(6)).created());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_exception WHERE code = 'E02' AND sku = ?", Integer.class, f.sku()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_exception WHERE code = 'E04' AND sku = ?", Integer.class, f.sku()));
    }

    @Test void failuresForTwoInventoryChannelsCreateIndependentExceptions() {
        Fixture f = fixture();
        String secondChannel = "inventory-second-" + UUID.randomUUID();
        products.addMapping(f.productId(), new ProductService.MappingInput(secondChannel,
            "SECOND-" + UUID.randomUUID(), MappingStatus.ACTIVE));
        Instant now = Instant.now();
        base(f, now.minusSeconds(10));
        var sale = events.ingest(new EventInput(f.salesChannel(), "S-" + UUID.randomUUID(), "SALE_CONFIRMED",
            now.minusSeconds(1), null, null, f.salesItem(), "O-MULTI", null, null, null,
            BigDecimal.ONE, null, null, Map.of())).event();
        var first = commands.create(CommandKind.INVENTORY_SYNC,
            new CommandService.CommandInput(sale.id(), f.inventoryChannel(), "first-" + sale.id(), true)).command();
        var second = commands.create(CommandKind.INVENTORY_SYNC,
            new CommandService.CommandInput(sale.id(), secondChannel, "second-" + sale.id(), true)).command();
        var evaluation = evaluator.evaluate(first.deadlineAt().isAfter(second.deadlineAt())
            ? first.deadlineAt().plusSeconds(1) : second.deadlineAt().plusSeconds(1));
        assertEquals(2, evaluation.created());
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM operational_exception WHERE code = 'E01' AND trigger_event_id = ?",
            Integer.class, sale.id()));
    }

    @Test void equalityAtToleranceAndMatchingReceiptDoNotCreateExceptions() {
        Fixture f = fixture();
        Instant at = Instant.now().minusSeconds(50);
        base(f, at);
        http.postForEntity("/api/v1/mock/physical", new MockPhysicalAdapter.PhysicalNotice(
            "R-" + UUID.randomUUID(), "physical", "RECEIPT", f.sku(), null, "R-EQUAL", null,
            new BigDecimal("3"), null, at.plusSeconds(1)), Map.class);
        http.postForEntity("/api/v1/mock/inventory", new MockInventoryAdapter.StockNotice(
            "I-" + UUID.randomUUID(), f.inventoryChannel(), f.inventoryItem(), null, "R-EQUAL",
            new BigDecimal("3"), new BigDecimal("103"), at.plusSeconds(2)), Map.class);
        http.postForEntity("/api/v1/mock/physical", new MockPhysicalAdapter.PhysicalNotice(
            "C-" + UUID.randomUUID(), "physical", "COUNT", f.sku(), null, null, null,
            new BigDecimal("102"), true, at.plusSeconds(3)), Map.class);
        assertEquals(0, evaluator.evaluate(at.plusSeconds(4)).created());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operational_exception WHERE sku = ?", Integer.class, f.sku()));
    }

    @Test void fiscalCommandRejectsWrongReferenceAndAcceptsExternalInvoice() {
        Fixture f = fixture();
        Instant now = Instant.now();
        var exit = new MockPhysicalAdapter.PhysicalNotice("EXIT-" + UUID.randomUUID(), "physical", "EXIT",
            f.sku(), "O3", null, "M3", BigDecimal.ONE, null, now.minusSeconds(1));
        var response = http.postForEntity("/api/v1/mock/physical", exit, Map.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        UUID exitId = UUID.fromString(response.getBody().get("id").toString());
        var command = commands.create(CommandKind.FISCAL,
            new CommandService.CommandInput(exitId, f.fiscalChannel(), "fiscal-" + exitId, false)).command();
        Instant issued = command.requestedAt().plusSeconds(1);
        var wrong = new MockFiscalAdapter.FiscalNotice("F-" + UUID.randomUUID(), f.fiscalChannel(),
            "INV-X", "OTHER", "M3", f.fiscalItem(), BigDecimal.ONE, issued);
        http.postForEntity("/api/v1/mock/fiscal", wrong, Map.class);
        assertEquals(CommandStatus.PENDING_CONFIRMATION, commands.get(command.id()).status());
        var right = new MockFiscalAdapter.FiscalNotice("F-" + UUID.randomUUID(), f.fiscalChannel(),
            "INV-3", "O3", "M3", f.fiscalItem(), BigDecimal.ONE, issued);
        http.postForEntity("/api/v1/mock/fiscal", right, Map.class);
        assertEquals(CommandStatus.CONFIRMED, commands.get(command.id()).status());
        assertEquals("INV-3", commands.get(command.id()).externalDocumentId());
        Map<?, ?> context = http.getForObject("/api/v1/products/" + f.productId() + "/context", Map.class);
        assertEquals("CONFIRMED", ((Map<?, ?>) context.get("fiscal")).get("status"));
        assertEquals(command.id().toString(), ((Map<?, ?>) context.get("fiscal")).get("commandId"));
        assertEquals(0, evaluator.evaluate(command.deadlineAt().plusSeconds(1)).created());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, http.postForEntity("/api/v1/mock/physical",
            new MockPhysicalAdapter.PhysicalNotice("bad", "physical", "UNKNOWN", f.sku(), null,
                null, null, BigDecimal.ONE, null, now), Map.class).getStatusCode());
    }

    @Test void fiscalTimeoutCreatesE03AndLateDocumentDoesNotEraseAuditTrail() {
        Fixture f = fixture();
        Instant now = Instant.now();
        var exit = events.ingest(new EventInput("physical", "EXIT-" + UUID.randomUUID(), "PHYSICAL_EXIT",
            now.minusSeconds(1), f.productId(), f.sku(), null, "O4", null, null, "M4", BigDecimal.ONE,
            null, null, Map.of())).event();
        var command = commands.create(CommandKind.FISCAL,
            new CommandService.CommandInput(exit.id(), f.fiscalChannel(), "fiscal-" + exit.id(), false)).command();
        http.postForEntity("/api/v1/mock/fiscal", new MockFiscalAdapter.FiscalNotice(
            "WRONG-" + UUID.randomUUID(), f.fiscalChannel(), "INV-WRONG", "OTHER", "M4",
            f.fiscalItem(), BigDecimal.ONE, command.requestedAt().plusSeconds(1)), Map.class);
        assertEquals(0, evaluator.evaluate(command.deadlineAt().minusNanos(1)).created());
        var timeout = evaluator.evaluate(command.deadlineAt());
        assertEquals(1, timeout.created());
        List<?> fiscalExceptions = http.getForObject("/api/v1/exceptions?code=E03", List.class);
        Map<?, ?> exception = (Map<?, ?>) fiscalExceptions.stream()
            .map(row -> (Map<?, ?>) row)
            .filter(row -> command.id().toString().equals(row.get("operationCommandId")))
            .findFirst().orElseThrow();
        assertTrue(((List<?>) exception.get("evidence")).stream()
            .anyMatch(row -> "referenceMismatch".equals(((Map<?, ?>) row).get("label"))));
        assertEquals(CommandStatus.TIMED_OUT, commands.get(command.id()).status());
        var late = new MockFiscalAdapter.FiscalNotice("LATE-" + UUID.randomUUID(), f.fiscalChannel(),
            "INV-LATE", "O4", "M4", f.fiscalItem(), BigDecimal.ONE, command.deadlineAt().plusSeconds(1));
        http.postForEntity("/api/v1/mock/fiscal", late, Map.class);
        assertEquals(CommandStatus.CONFIRMED, commands.get(command.id()).status());
        assertEquals(0, evaluator.evaluate(command.deadlineAt().plusSeconds(2)).created());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_exception WHERE code = 'E03' AND trigger_event_id = ?", Integer.class, exit.id()));
    }

    @Test void fiscalDispatchFailureCanBeRetriedIdempotentlyWithHistory() {
        Fixture f = fixture();
        Instant now = Instant.now();
        var exit = events.ingest(new EventInput("physical", "EXIT-" + UUID.randomUUID(), "PHYSICAL_EXIT",
            now.minusSeconds(1), f.productId(), f.sku(), null, "O-RETRY", null, null, "M-RETRY",
            BigDecimal.ONE, null, null, Map.of())).event();
        var failed = commands.create(CommandKind.FISCAL,
            new CommandService.CommandInput(exit.id(), f.fiscalChannel(), "fiscal-" + exit.id(), true)).command();
        assertEquals(CommandStatus.FAILED, failed.status());
        assertEquals("MOCK_FISCAL_DISPATCH_FAILURE", failed.attempts().get(0).errorCode());
        var retried = commands.retry(failed.id(), "fiscal-retry-1", false);
        assertEquals(CommandStatus.PENDING_CONFIRMATION, retried.status());
        assertEquals(2, retried.attempts().size());
        assertEquals(2, commands.retry(failed.id(), "fiscal-retry-1", false).attempts().size());
        var invoice = new MockFiscalAdapter.FiscalNotice("F-" + UUID.randomUUID(), f.fiscalChannel(),
            "INV-RETRY", "O-RETRY", "M-RETRY", f.fiscalItem(), BigDecimal.ONE,
            retried.requestedAt().plusSeconds(1));
        http.postForEntity("/api/v1/mock/fiscal", invoice, Map.class);
        assertEquals(CommandStatus.CONFIRMED, commands.get(failed.id()).status());
        assertEquals(2, commands.get(failed.id()).attempts().size());
    }

    @Test void fiscalMovementCorrelationCanBeConfiguredWithoutAcceptingWrongMovement() {
        Fixture f = fixture();
        String previous = jdbc.queryForObject("SELECT fiscal_correlation_key FROM demo_configuration WHERE id = 1", String.class);
        jdbc.update("UPDATE demo_configuration SET fiscal_correlation_key = 'MOVEMENT_ID_AND_SKU' WHERE id = 1");
        try {
            Instant now = Instant.now();
            var exit = events.ingest(new EventInput("physical", "EXIT-" + UUID.randomUUID(), "PHYSICAL_EXIT",
                now.minusSeconds(1), f.productId(), f.sku(), null, "O5", null, null, "M5", BigDecimal.ONE,
                null, null, Map.of())).event();
            var command = commands.create(CommandKind.FISCAL,
                new CommandService.CommandInput(exit.id(), f.fiscalChannel(), "fiscal-" + exit.id(), false)).command();
            var wrong = new MockFiscalAdapter.FiscalNotice("WRONG-" + UUID.randomUUID(), f.fiscalChannel(),
                "INV-WRONG", "O5", "M6", f.fiscalItem(), BigDecimal.ONE, command.requestedAt().plusSeconds(1));
            http.postForEntity("/api/v1/mock/fiscal", wrong, Map.class);
            assertEquals(CommandStatus.PENDING_CONFIRMATION, commands.get(command.id()).status());
            var right = new MockFiscalAdapter.FiscalNotice("RIGHT-" + UUID.randomUUID(), f.fiscalChannel(),
                "INV-RIGHT", "DIFFERENT-ORDER", "M5", f.fiscalItem(), BigDecimal.ONE, command.requestedAt().plusSeconds(2));
            http.postForEntity("/api/v1/mock/fiscal", right, Map.class);
            assertEquals(CommandStatus.CONFIRMED, commands.get(command.id()).status());
            assertEquals(0, evaluator.evaluate(command.deadlineAt().plusSeconds(1)).created());
        } finally {
            jdbc.update("UPDATE demo_configuration SET fiscal_correlation_key = ? WHERE id = 1", previous);
        }
    }

    private void base(Fixture f, Instant at) {
        events.ingest(new EventInput("baseline-" + UUID.randomUUID(), "base", "STOCK_UPDATED", at,
            f.productId(), f.sku(), null, null, null, null, null, null,
            new BigDecimal("100"), null, Map.of()));
    }
}
