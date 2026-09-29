package io.peek.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.peek.core.events.EventInput;
import io.peek.core.events.EventService;
import io.peek.core.exceptions.ExceptionCode;
import io.peek.core.exceptions.ExceptionService;
import io.peek.core.exceptions.ExceptionService.EvidenceDraft;
import io.peek.core.exceptions.ExceptionService.ExceptionDraft;
import io.peek.core.exceptions.Severity;
import io.peek.core.products.MappingStatus;
import io.peek.core.products.ProductService.MappingInput;
import io.peek.core.products.ProductService.ProductInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendCoreIT {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("PEEK_TEST_DB_URL", "jdbc:postgresql://localhost:5432/peek_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PEEK_TEST_DB_USER", "peek"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("PEEK_TEST_DB_PASSWORD", "peek_local_only"));
    }

    @Autowired TestRestTemplate http;
    @Autowired EventService events;
    @Autowired ExceptionService exceptions;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @LocalServerPort int port;

    private final Instant occurred = Instant.parse("2026-01-01T12:00:00Z");

    @Test void healthAndMigrationWorkOnPostgresAndRerunIsNoOp() {
        assertEquals(HttpStatus.OK, http.getForEntity("/actuator/health", Map.class).getStatusCode());
        assertEquals("UP", http.getForObject("/actuator/health", Map.class).get("status"));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM demo_configuration", Integer.class));
    }

    @Test void productMappingResolutionIdempotencyAndConflicts() {
        String sku = "P-" + UUID.randomUUID();
        ProductInput input = new ProductInput(sku, "Fictitious item", null, new BigDecimal("12.00"), null, null);
        var created = http.postForEntity("/api/v1/products", input, Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        UUID productId = UUID.fromString((String) created.getBody().get("id"));
        assertEquals(HttpStatus.OK, http.postForEntity("/api/v1/products", input, Map.class).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, http.postForEntity("/api/v1/products",
            new ProductInput(sku, "Different", null, null, null, null), Map.class).getStatusCode());

        String externalId = "EXT-" + UUID.randomUUID();
        String path = "/api/v1/products/" + productId + "/mappings";
        MappingInput mapping = new MappingInput("demo-inventory", externalId, MappingStatus.ACTIVE);
        var mapped = http.postForEntity(path, mapping, Map.class);
        assertEquals(HttpStatus.CREATED, mapped.getStatusCode());
        assertEquals(HttpStatus.OK, http.postForEntity(path, mapping, Map.class).getStatusCode());
        assertEquals(sku, http.getForObject("/api/v1/product-mappings/resolve?channel=demo-inventory&externalId=" + externalId,
            Map.class).get("sku"));
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/api/v1/product-mappings/resolve?channel=demo-inventory&externalId=missing",
            Map.class).getStatusCode());

        var second = http.postForEntity("/api/v1/products", new ProductInput("P-" + UUID.randomUUID(), "Other", null, null, null, null), Map.class);
        assertEquals(HttpStatus.CONFLICT, http.postForEntity("/api/v1/products/" + second.getBody().get("id") + "/mappings",
            mapping, Map.class).getStatusCode());

        HttpHeaders headers = new HttpHeaders();
        headers.set("If-Match-Version", mapped.getBody().get("version").toString());
        String mappingId = (String) mapped.getBody().get("id");
        var inactive = http.exchange("/api/v1/product-mappings/" + mappingId, HttpMethod.PUT,
            new HttpEntity<>(new MappingInput("demo-inventory", externalId, MappingStatus.INACTIVE), headers), Map.class);
        assertEquals(HttpStatus.OK, inactive.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, http.getForEntity("/api/v1/product-mappings/resolve?channel=demo-inventory&externalId=" + externalId,
            Map.class).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, http.exchange("/api/v1/product-mappings/" + mappingId, HttpMethod.PUT,
            new HttpEntity<>(mapping, headers), Map.class).getStatusCode());
    }

    @Test void eventIngestionDistinguishesDuplicateInvalidUnsupportedAndConflict() {
        String source = "demo-" + UUID.randomUUID();
        String external = "sale-1";
        EventInput sale = event(source, external, "SALE_CONFIRMED", "A", "5", null, "O1", null, null, null);
        var first = http.postForEntity("/api/v1/events", sale, Map.class);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        var duplicate = http.postForEntity("/api/v1/events", sale, Map.class);
        assertEquals(HttpStatus.OK, duplicate.getStatusCode());
        assertEquals(first.getBody().get("id"), duplicate.getBody().get("id"));
        assertEquals(first.getBody().get("receivedAt"), duplicate.getBody().get("receivedAt"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM normalized_event WHERE source = ? AND external_event_id = ?",
            Integer.class, source, external));
        assertEquals(HttpStatus.CONFLICT, http.postForEntity("/api/v1/events",
            event(source, external, "SALE_CONFIRMED", "A", "6", null, "O1", null, null, null), Map.class).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, http.postForEntity("/api/v1/events",
            event(source, "bad-type", "UNKNOWN", "A", "1", null, null, null, null, null), Map.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity("/api/v1/events",
            event(source, "bad-sale", "SALE_CONFIRMED", "A", "0", null, "O1", null, null, null), Map.class).getStatusCode());
    }

    @Test void stockAndContextExposeDistinctEvidenceBackedStates() {
        String sku = "S-" + UUID.randomUUID();
        String source = "inventory-" + UUID.randomUUID();
        var product = http.postForEntity("/api/v1/products", new ProductInput(sku, "Stock item", null, null, null, null), Map.class);
        ingest(source, "base", "STOCK_UPDATED", sku, null, "100", null, null, null, null);
        ingest(source, "receipt", "GOODS_RECEIVED", sku, "20", null, null, "R1", null, null);
        ingest(source, "sale", "SALE_CONFIRMED", sku, "15", null, "O1", null, null, null);
        ingest(source, "adjust", "STOCK_ADJUSTED", sku, "-2", null, null, null, null, null);
        Map<?, ?> stock = http.getForObject("/api/v1/stocks/" + sku, Map.class);
        assertEquals(0, new BigDecimal(stock.get("expectedStock").toString()).compareTo(new BigDecimal("103")));
        assertEquals(0, new BigDecimal(stock.get("systemStock").toString()).compareTo(new BigDecimal("100")));
        assertEquals(4, ((List<?>) stock.get("usedEventIds")).size());
        Map<?, ?> context = http.getForObject("/api/v1/products/" + product.getBody().get("id") + "/context", Map.class);
        assertEquals("NOT_AVAILABLE", ((Map<?, ?>) context.get("inventorySync")).get("status"));
        assertEquals("UNKNOWN", ((Map<?, ?>) context.get("fiscal")).get("status"));
    }

    @Test void mappedEventResolvesCanonicalProductAndFiscalRequiresEvidence() {
        String sku = "M-" + UUID.randomUUID();
        String externalId = "X-" + UUID.randomUUID();
        String source = "mapped-" + UUID.randomUUID();
        var product = http.postForEntity("/api/v1/products", new ProductInput(sku, "Mapped item", null, null, null, null), Map.class);
        UUID productId = UUID.fromString((String) product.getBody().get("id"));
        http.postForEntity("/api/v1/products/" + productId + "/mappings",
            new MappingInput(source, externalId, MappingStatus.ACTIVE), Map.class);
        EventInput mapped = new EventInput(source, "mapped-sale", "SALE_CONFIRMED", occurred, null, null,
            externalId, "O-42", null, null, null, BigDecimal.ONE, null, null, Map.of());
        var created = http.postForEntity("/api/v1/events", mapped, Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals(productId.toString(), created.getBody().get("productId"));
        assertEquals(sku, created.getBody().get("sku"));
        assertEquals(externalId, created.getBody().get("externalProductId"));
        assertEquals(HttpStatus.NOT_FOUND, http.postForEntity("/api/v1/events",
            new EventInput(source, "unknown-mapping", "SALE_CONFIRMED", occurred, null, null,
                "missing", "O-42", null, null, null, BigDecimal.ONE, null, null, Map.of()), Map.class).getStatusCode());
        ingest(source, "invoice", "INVOICE_ISSUED", sku, null, null, "O-42", null, "INV-42", null);
        Map<?, ?> context = http.getForObject("/api/v1/products/" + productId + "/context", Map.class);
        assertEquals("CONFIRMED", ((Map<?, ?>) context.get("fiscal")).get("status"));
        assertEquals("INV-42", ((Map<?, ?>) context.get("fiscal")).get("documentId"));
    }

    @Test void exceptionDetailFilterResolutionAndEvidenceAreIdempotent() {
        String sku = "E-" + UUID.randomUUID();
        var trigger = events.ingest(event("physical-" + UUID.randomUUID(), "count", "PHYSICAL_COUNT",
            sku, "93", null, null, null, null, true)).event();
        ExceptionDraft draft = new ExceptionDraft(ExceptionCode.E02, trigger.id(), Severity.WARNING,
            "Physical stock differs", null, sku, null, "95", "93", "tolerance=1", null,
            "Recount the item", List.of(new EvidenceDraft(trigger.id(), "PHYSICAL_COUNT", trigger.source(),
                "Physical count", "93", trigger.occurredAt())));
        var created = exceptions.create(draft);
        assertEquals(created.id(), exceptions.create(draft).id());
        var detail = http.getForEntity("/api/v1/exceptions/" + created.id(), Map.class);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        assertEquals("95", detail.getBody().get("expectedState"));
        assertEquals(1, ((List<?>) detail.getBody().get("evidence")).size());
        var list = http.getForObject("/api/v1/exceptions?status=OPEN&code=E02", List.class);
        assertTrue(list.stream().anyMatch(row -> created.id().toString().equals(((Map<?, ?>) row).get("id"))));
        String path = "/api/v1/exceptions/" + created.id() + "/resolve";
        var resolved = http.postForEntity(path, Map.of("note", "Count verified"), Map.class);
        assertEquals(HttpStatus.OK, resolved.getStatusCode());
        assertEquals("RESOLVED", resolved.getBody().get("status"));
        assertNotNull(resolved.getBody().get("resolvedAt"));
        assertEquals(resolved.getBody().get("resolvedAt"), http.postForEntity(path, Map.of("note", "Count verified"), Map.class).getBody().get("resolvedAt"));
        assertEquals(HttpStatus.CONFLICT, http.postForEntity(path, Map.of("note", "Other"), Map.class).getStatusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_exception WHERE code = 'E02' AND trigger_event_id = ?",
            Integer.class, trigger.id()));
    }

    private void ingest(String source, String externalId, String type, String sku, String quantity, String stockAfter,
                        String order, String receipt, String invoice, Boolean confirmed) {
        assertTrue(events.ingest(event(source, externalId, type, sku, quantity, stockAfter, order, receipt, invoice, confirmed)).created());
    }
    private EventInput event(String source, String externalId, String type, String sku, String quantity, String stockAfter,
                             String order, String receipt, String invoice, Boolean confirmed) {
        return new EventInput(source, externalId, type, occurred, null, sku, null, order, invoice, receipt, null,
            quantity == null ? null : new BigDecimal(quantity), stockAfter == null ? null : new BigDecimal(stockAfter),
            confirmed, Map.of());
    }
}
