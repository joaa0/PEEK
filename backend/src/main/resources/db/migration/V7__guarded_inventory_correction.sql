ALTER TABLE operation_command DROP CONSTRAINT operation_command_kind_check,
    ADD CONSTRAINT operation_command_kind_check CHECK (kind IN ('INVENTORY_SYNC', 'INVENTORY_CORRECTION', 'FISCAL'));

ALTER TABLE agent_action_execution
    ADD COLUMN mapping_id UUID REFERENCES product_channel_mapping(id),
    ADD COLUMN target_stock NUMERIC(15,3),
    DROP CONSTRAINT agent_action_kind_check,
    ADD CONSTRAINT agent_action_kind_check CHECK (
        (tool_name = 'peek_retry_inventory_sync' AND action_type = 'RETRY_INVENTORY_SYNC'
            AND command_id IS NOT NULL AND physical_count_event_id IS NULL)
        OR (tool_name = 'peek_apply_e02_reconciliation' AND action_type = 'ACCEPT_PHYSICAL_CHECKPOINT'
            AND command_id IS NULL AND physical_count_event_id IS NOT NULL
            AND decision_fingerprint IS NOT NULL AND verification_deadline_at IS NOT NULL)
        OR (tool_name = 'peek_correct_inventory_stock' AND action_type = 'CORRECT_INVENTORY_STOCK'
            AND command_id IS NOT NULL AND physical_count_event_id IS NOT NULL AND mapping_id IS NOT NULL
            AND target_stock IS NOT NULL AND target_stock >= 0
            AND decision_fingerprint IS NOT NULL AND verification_deadline_at IS NOT NULL)
    );
CREATE UNIQUE INDEX agent_correction_exception_mapping_idx ON agent_action_execution(exception_id, mapping_id)
    WHERE tool_name = 'peek_correct_inventory_stock';
CREATE UNIQUE INDEX agent_correction_checkpoint_mapping_idx ON agent_action_execution(physical_count_event_id, mapping_id)
    WHERE tool_name = 'peek_correct_inventory_stock';

-- Separate simulated destination effect; never treated as a canonical confirmation event.
CREATE TABLE mock_inventory_correction (
    command_id UUID PRIMARY KEY REFERENCES operation_command(id),
    mapping_id UUID NOT NULL REFERENCES product_channel_mapping(id),
    channel VARCHAR(100) NOT NULL,
    external_product_id VARCHAR(200) NOT NULL,
    target_stock NUMERIC(15,3) NOT NULL CHECK (target_stock >= 0)
);
