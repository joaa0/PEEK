package io.peek.core.integrations;

import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.OutboundCommandAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Simulated external stock write. Acceptance still requires a separately ingested STOCK_UPDATED. */
@Component
public class MockInventoryCorrectionOutboundAdapter implements OutboundCommandAdapter {
    private final JdbcTemplate jdbc;
    public MockInventoryCorrectionOutboundAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public CommandKind kind() { return CommandKind.INVENTORY_CORRECTION; }
    @Override public DispatchResult dispatch(DispatchRequest request) {
        if (request.simulateFailure()) return new DispatchResult(false, null, "MOCK_CORRECTION_FAILURE", "Simulated correction failure");
        jdbc.update("""
            INSERT INTO mock_inventory_correction (command_id, mapping_id, channel, external_product_id, target_stock)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT (command_id) DO NOTHING
            """, request.commandId(), request.mappingId(), request.channel(), request.externalProductId(), request.expectedStock());
        return new DispatchResult(true, "mock-correction-" + request.commandId(), null, null);
    }
}
