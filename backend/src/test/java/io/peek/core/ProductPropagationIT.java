package io.peek.core;

import static org.junit.jupiter.api.Assertions.*;
import io.peek.core.products.*;
import io.peek.core.propagation.PropagationService;
import io.peek.core.shared.ConflictException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProductPropagationIT.TimeConfiguration.class)
class ProductPropagationIT {
    static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("PEEK_TEST_DB_URL", "jdbc:postgresql://localhost:5432/peek_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("PEEK_TEST_DB_USER", "peek"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("PEEK_TEST_DB_PASSWORD", "peek_local_only"));
    }
    @Autowired ProductService products;
    @Autowired PropagationService propagation;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    ProductService.ProductView product() {
        return products.create(new ProductService.ProductInput("PROP-" + UUID.randomUUID(),
            "Fictitious product", "Minimal", null, null, "Demo")).value();
    }
    PropagationService.Input input(ProductService.ProductView p, String key, boolean fail) {
        return new PropagationService.Input(key, p.version(), List.of(
            new PropagationService.Target("ERP", false), new PropagationService.Target("MERCADO_LIVRE", false),
            new PropagationService.Target("SHOPEE", fail)));
    }
    @Test void partialSuccessPersistsMappingsEvidenceAndRetryWithoutDuplicateEffects() {
        var p = product(); String key = UUID.randomUUID().toString();
        var rows = propagation.start(p.id(), input(p, key, true));
        assertEquals(List.of("SUCCEEDED", "SUCCEEDED", "FAILED"), rows.stream().map(r -> r.status()).toList());
        assertEquals(3, rows.stream().map(r -> r.id()).distinct().count());
        assertTrue(rows.stream().allMatch(r -> r.requestedAt().equals(NOW) && r.attempts().size() == 1));
        var failed = rows.get(2);
        assertNotNull(failed.attempts().get(0).errorCode());
        assertEquals("PENDING", products.mappings(p.id()).stream().filter(m -> m.channel().equals("SHOPEE")).findFirst().get().status().name());
        assertEquals(rows.stream().map(r -> r.id()).toList(), propagation.start(p.id(), input(p, key, true)).stream().map(r -> r.id()).toList());
        var retried = propagation.retry(failed.id(), new PropagationService.RetryInput("retry-1", false));
        assertEquals("SUCCEEDED", retried.status()); assertEquals(2, retried.attempts().size());
        assertEquals("FAILED", retried.attempts().get(0).result());
        assertEquals(3, retried.evidence().size());
        assertEquals(retried.externalId(), products.resolve("SHOPEE", retried.externalId()).externalId());
        assertEquals(2, propagation.retry(failed.id(), new PropagationService.RetryInput("retry-1", false)).attempts().size());
        assertThrows(ConflictException.class, () -> propagation.retry(failed.id(), new PropagationService.RetryInput("retry-2", false)));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM mock_destination_product WHERE product_id = ?", Integer.class, p.id()));
    }
    @Test void editCreatesUpdateCommandWithStableExternalIdentityAndImmutableHistory() {
        var p = product();
        var first = propagation.start(p.id(), input(p, "create-" + p.id(), false));
        var updated = products.update(p.id(), new ProductService.ProductInput(p.sku(), "Updated fictitious product", null, null, null, "Demo"), p.version());
        var second = propagation.start(p.id(), input(updated, "update-" + p.id(), false));
        assertTrue(second.stream().allMatch(r -> r.operation().equals("UPDATE")));
        assertEquals(first.stream().map(r -> r.externalId()).toList(), second.stream().map(r -> r.externalId()).toList());
        assertEquals(6, propagation.list(p.id()).size());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM mock_destination_product WHERE product_id = ?", Integer.class, p.id()));
        assertEquals(first.get(0).productVersion(), propagation.get(first.get(0).id()).productVersion());
    }
    @Test void staleVersionInactiveMappingAndConflictingKeysRejectBeforeDispatch() {
        var p = product();
        products.addMapping(p.id(), new ProductService.MappingInput("SHOPEE", "inactive-" + p.id(), MappingStatus.INACTIVE));
        assertThrows(ConflictException.class, () -> propagation.start(p.id(), input(p, "inactive-" + p.id(), false)));
        assertEquals(0, propagation.list(p.id()).size());
        var other = product(); String key = "global-" + UUID.randomUUID();
        propagation.start(other.id(), input(other, key, false));
        assertThrows(ConflictException.class, () -> propagation.start(p.id(), input(p, key, false)));
        assertThrows(ConflictException.class, () -> propagation.start(other.id(), input(other, key, true)));
        assertThrows(ConflictException.class, () -> propagation.start(other.id(), new PropagationService.Input("stale", 100,
            List.of(new PropagationService.Target("ERP", false)))));
        assertThrows(IllegalArgumentException.class, () -> propagation.start(other.id(), new PropagationService.Input("bad", 0,
            List.of(new PropagationService.Target("UNSUPPORTED", false)))));
    }
    @Test void retryOfOldSnapshotDoesNotOverwriteNewProductVersion() {
        var p = product(); var failed = propagation.start(p.id(), input(p, "old-" + p.id(), true)).get(2);
        products.update(p.id(), new ProductService.ProductInput(p.sku(), "New name", null, null, null, "Demo"), p.version());
        assertThrows(ConflictException.class, () -> propagation.retry(failed.id(), new PropagationService.RetryInput("retry", false)));
        assertEquals(1, propagation.get(failed.id()).attempts().size());
    }
    @Test void httpContractExposesIndependentCommandsAndInvestigationEvidence() {
        var p = product();
        var response = http.postForEntity("/api/v1/products/" + p.id() + "/propagations", input(p, "http-" + p.id(), true), List.class);
        assertEquals(200, response.getStatusCode().value());
        var failed = (Map<?, ?>) response.getBody().get(2);
        var read = http.getForObject("/api/v1/product-propagations/" + failed.get("id"), Map.class);
        assertEquals("FAILED", read.get("status")); assertFalse(((List<?>) read.get("evidence")).isEmpty());
        assertEquals(200, http.postForEntity("/api/v1/product-propagations/" + failed.get("id") + "/retry",
            new PropagationService.RetryInput("http-retry", false), Map.class).getStatusCode().value());
        assertEquals(3, http.getForObject("/api/v1/products/" + p.id() + "/propagations", List.class).size());
    }
}
