package io.peek.core.integrations;

import io.peek.core.propagation.ProductDestinationAdapter;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Only the destination effect is mocked; commands, attempts and mappings are persisted. */
@Component
public class MockProductDestinationAdapter implements ProductDestinationAdapter {
    public static final Set<String> CHANNELS = Set.of("ERP", "MERCADO_LIVRE", "SHOPEE");
    private final JdbcTemplate jdbc;
    public MockProductDestinationAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean supports(String channel) { return CHANNELS.contains(channel); }
    public Result dispatch(Request request) {
        if (request.simulateFailure()) return new Result(null, "SIMULATED_DESTINATION_FAILURE",
            "Simulated destination rejected the product. Review and retry explicitly.");
        String externalId = request.externalId() == null
            ? "SIM-" + request.channel() + "-" + request.productId() : request.externalId();
        // Repeated create/update affects one destination record, with a stable external identity.
        jdbc.update("""
            INSERT INTO mock_destination_product
                (id, product_id, channel, external_id, snapshot_json, product_version)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (product_id, channel) DO UPDATE
                SET snapshot_json = EXCLUDED.snapshot_json, product_version = EXCLUDED.product_version
            """, UUID.randomUUID(), request.productId(), request.channel(), externalId,
            request.snapshotJson(), request.productVersion());
        String storedId = jdbc.queryForObject(
            "SELECT external_id FROM mock_destination_product WHERE product_id = ? AND channel = ?",
            String.class, request.productId(), request.channel());
        return new Result(storedId, null, null);
    }
}
