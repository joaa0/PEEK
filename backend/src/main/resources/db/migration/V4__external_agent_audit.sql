ALTER TABLE operational_exception
    ADD COLUMN reconciliation_event_id UUID REFERENCES normalized_event(id),
    ADD COLUMN reconciled_at TIMESTAMPTZ;

CREATE TABLE agent_action_execution (
    id UUID PRIMARY KEY,
    exception_id UUID NOT NULL REFERENCES operational_exception(id),
    command_id UUID NOT NULL REFERENCES operation_command(id),
    agent_type VARCHAR(50) NOT NULL,
    tool_name VARCHAR(100) NOT NULL CHECK (tool_name = 'peek_retry_inventory_sync'),
    action_type VARCHAR(50) NOT NULL CHECK (action_type = 'RETRY_INVENTORY_SYNC'),
    idempotency_key VARCHAR(200) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    execution_status VARCHAR(30) NOT NULL CHECK (execution_status IN ('SUCCEEDED', 'FAILED')),
    verification_status VARCHAR(30) NOT NULL CHECK (verification_status IN ('PENDING_VERIFICATION', 'VERIFIED', 'FAILED')),
    input_summary TEXT NOT NULL,
    output_summary TEXT NOT NULL,
    UNIQUE (command_id, tool_name, idempotency_key)
);
CREATE INDEX agent_action_exception_idx ON agent_action_execution(exception_id, started_at);
