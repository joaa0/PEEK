package io.peek.core.demo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoResetService {
    private static final Pattern RUN_ID = Pattern.compile("^(?:DEMO|QA)-[A-Za-z0-9-]{6,80}$");

    private final JdbcTemplate jdbc;

    public DemoResetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ResetResult(String runId, int evidence, int exceptions, int attempts, int commands,
                              int events, int mappingAudit, int mappings, int productAudit, int products,
                              int propagationAttempts, int propagationCommands, int destinationProducts,
                              int agentActions) {
        @JsonProperty("totalDeleted")
        public int totalDeleted() {
            return evidence + exceptions + attempts + commands + events + mappingAudit + mappings
                + productAudit + products + propagationAttempts + propagationCommands + destinationProducts + agentActions;
        }
    }

    @Transactional
    public ResetResult reset(String runId) {
        String normalized = validate(runId);
        List<UUID> productIds = ids(
            "SELECT id FROM product WHERE sku IN (?, ?, ?) AND category = 'Demo'",
            "CAM-" + normalized, "SKU-E02-" + normalized, "SKU-E04-" + normalized);
        // The canonical product is the ownership boundary. A substring of an external
        // event ID is not: DEMO-ABCDEF would also match DEMO-ABCDEF1.
        List<UUID> eventIds = productIds.isEmpty() ? List.of()
            : idsIn("SELECT id FROM normalized_event WHERE product_id IN (%s)", productIds);

        List<UUID> commandIds = new ArrayList<>();
        if (!eventIds.isEmpty()) {
            commandIds.addAll(idsIn(
                "SELECT id FROM operation_command WHERE trigger_event_id IN (%s) OR confirmation_event_id IN (%s)",
                eventIds, eventIds));
        }
        if (!productIds.isEmpty()) {
            commandIds.addAll(idsIn("SELECT id FROM operation_command WHERE product_id IN (%s)", productIds));
        }
        commandIds = commandIds.stream().distinct().toList();

        List<UUID> exceptionIds = new ArrayList<>();
        if (!eventIds.isEmpty()) {
            exceptionIds.addAll(idsIn(
                "SELECT DISTINCT exception.id FROM operational_exception exception"
                    + " LEFT JOIN exception_evidence evidence ON evidence.exception_id = exception.id"
                    + " WHERE exception.trigger_event_id IN (%s) OR evidence.event_id IN (%s)",
                eventIds, eventIds));
        }
        if (!commandIds.isEmpty()) {
            exceptionIds.addAll(idsIn(
                "SELECT id FROM operational_exception WHERE operation_command_id IN (%s)", commandIds));
        }
        if (!productIds.isEmpty()) {
            exceptionIds.addAll(idsIn(
                "SELECT id FROM operational_exception WHERE product_id IN (%s)", productIds));
        }
        exceptionIds = exceptionIds.stream().distinct().toList();

        List<UUID> mappingIds = productIds.isEmpty() ? List.of()
            : idsIn("SELECT id FROM product_channel_mapping WHERE product_id IN (%s)", productIds);

        int agentActions = deleteIn("DELETE FROM agent_action_execution WHERE command_id IN (%s)", commandIds);
        agentActions += deleteIn("DELETE FROM agent_action_execution WHERE exception_id IN (%s)", exceptionIds);
        int evidence = deleteIn("DELETE FROM exception_evidence WHERE exception_id IN (%s)", exceptionIds);
        int exceptions = deleteIn("DELETE FROM operational_exception WHERE id IN (%s)", exceptionIds);
        deleteIn("DELETE FROM mock_inventory_correction WHERE command_id IN (%s)", commandIds);
        int attempts = deleteIn("DELETE FROM operation_attempt WHERE command_id IN (%s)", commandIds);
        int commands = deleteIn("DELETE FROM operation_command WHERE id IN (%s)", commandIds);
        int events = deleteIn("DELETE FROM normalized_event WHERE id IN (%s)", eventIds);
        int propagationAttempts = productIds.isEmpty() ? 0 : jdbc.update(
            "DELETE FROM product_propagation_attempt WHERE command_id IN"
                + " (SELECT id FROM product_propagation_command WHERE product_id IN ("
                + placeholders(productIds.size()) + "))", productIds.toArray());
        int propagationCommands = deleteIn("DELETE FROM product_propagation_command WHERE product_id IN (%s)", productIds);
        int destinationProducts = deleteIn("DELETE FROM mock_destination_product WHERE product_id IN (%s)", productIds);
        int mappingAudit = deleteIn(
            "DELETE FROM product_channel_mapping_audit WHERE mapping_id IN (%s)", mappingIds);
        int mappings = deleteIn("DELETE FROM product_channel_mapping WHERE id IN (%s)", mappingIds);
        int productAudit = deleteIn("DELETE FROM product_audit WHERE product_id IN (%s)", productIds);
        int products = deleteIn("DELETE FROM product WHERE id IN (%s)", productIds);

        return new ResetResult(normalized, evidence, exceptions, attempts, commands, events,
            mappingAudit, mappings, productAudit, products, propagationAttempts, propagationCommands, destinationProducts, agentActions);
    }

    private String validate(String runId) {
        if (runId == null || !RUN_ID.matcher(runId.trim()).matches()) {
            throw new IllegalArgumentException("runId must use the DEMO-* or QA-* demo namespace");
        }
        return runId.trim();
    }

    private List<UUID> ids(String sql, Object... arguments) {
        return jdbc.query(sql, (row, index) -> row.getObject(1, UUID.class), arguments);
    }

    private List<UUID> idsIn(String sql, List<UUID> ids) {
        return ids(sql.formatted(placeholders(ids.size())), ids.toArray());
    }

    private List<UUID> idsIn(String sql, List<UUID> first, List<UUID> second) {
        List<UUID> arguments = new ArrayList<>(first);
        arguments.addAll(second);
        return ids(sql.formatted(placeholders(first.size()), placeholders(second.size())), arguments.toArray());
    }

    private int deleteIn(String sql, List<UUID> ids) {
        if (ids.isEmpty()) return 0;
        return jdbc.update(sql.formatted(placeholders(ids.size())), ids.toArray());
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }
}
