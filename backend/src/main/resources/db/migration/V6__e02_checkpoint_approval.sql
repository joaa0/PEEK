-- E02 accepts an existing confirmed checkpoint; it has no outbound command.
ALTER TABLE agent_action_execution ALTER COLUMN command_id DROP NOT NULL;
ALTER TABLE agent_action_execution
    DROP CONSTRAINT agent_action_execution_tool_name_check,
    DROP CONSTRAINT agent_action_execution_action_type_check,
    ADD COLUMN physical_count_event_id UUID REFERENCES normalized_event(id),
    ADD COLUMN decision_fingerprint VARCHAR(64),
    ADD COLUMN verification_deadline_at TIMESTAMPTZ,
    ADD CONSTRAINT agent_action_kind_check CHECK (
        (tool_name = 'peek_retry_inventory_sync' AND action_type = 'RETRY_INVENTORY_SYNC'
            AND command_id IS NOT NULL AND physical_count_event_id IS NULL)
        OR (tool_name = 'peek_apply_e02_reconciliation' AND action_type = 'ACCEPT_PHYSICAL_CHECKPOINT'
            AND command_id IS NULL AND physical_count_event_id IS NOT NULL
            AND decision_fingerprint IS NOT NULL AND verification_deadline_at IS NOT NULL)
    );
-- A different key cannot repeat the same checkpoint decision.
CREATE UNIQUE INDEX agent_action_e02_decision_idx ON agent_action_execution(exception_id, tool_name)
    WHERE tool_name = 'peek_apply_e02_reconciliation';
