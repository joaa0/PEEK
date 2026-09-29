ALTER TABLE demo_configuration
    ADD COLUMN fiscal_correlation_key varchar(32) NOT NULL DEFAULT 'ORDER_ID_AND_SKU'
        CHECK (fiscal_correlation_key IN ('ORDER_ID_AND_SKU', 'MOVEMENT_ID_AND_SKU'));

CREATE TABLE operation_command (
    id uuid PRIMARY KEY,
    kind varchar(24) NOT NULL CHECK (kind IN ('INVENTORY_SYNC', 'FISCAL')),
    idempotency_key varchar(200) NOT NULL,
    trigger_event_id uuid NOT NULL REFERENCES normalized_event(id),
    product_id uuid NOT NULL REFERENCES product(id),
    mapping_id uuid NOT NULL REFERENCES product_channel_mapping(id),
    channel varchar(100) NOT NULL,
    external_product_id varchar(200) NOT NULL,
    sku varchar(100) NOT NULL,
    order_id varchar(200),
    movement_id varchar(200),
    requested_quantity numeric(15, 3) NOT NULL,
    expected_stock numeric(15, 3),
    requested_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('REQUESTED', 'PENDING_CONFIRMATION', 'FAILED', 'TIMED_OUT', 'CONFIRMED')),
    confirmation_event_id uuid REFERENCES normalized_event(id),
    confirmed_at timestamptz,
    external_document_id varchar(200),
    last_error_code varchar(100),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_operation_idempotency UNIQUE (kind, idempotency_key),
    CONSTRAINT uq_operation_trigger_channel UNIQUE (kind, trigger_event_id, channel),
    CONSTRAINT ck_operation_confirmation CHECK (
        (status = 'CONFIRMED' AND confirmation_event_id IS NOT NULL AND confirmed_at IS NOT NULL)
        OR (status <> 'CONFIRMED' AND confirmation_event_id IS NULL AND confirmed_at IS NULL)
    )
);
CREATE INDEX ix_operation_status_deadline ON operation_command(status, deadline_at);
CREATE INDEX ix_operation_product_kind ON operation_command(product_id, kind, requested_at DESC);

CREATE TABLE operation_attempt (
    id uuid PRIMARY KEY,
    command_id uuid NOT NULL REFERENCES operation_command(id),
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    dispatched_at timestamptz NOT NULL,
    responded_at timestamptz NOT NULL,
    result varchar(16) NOT NULL CHECK (result IN ('ACCEPTED', 'FAILED')),
    external_request_id varchar(200),
    error_code varchar(100),
    error_message varchar(500),
    CONSTRAINT uq_operation_attempt_number UNIQUE (command_id, attempt_number),
    CONSTRAINT ck_operation_attempt_result CHECK (
        (result = 'ACCEPTED' AND external_request_id IS NOT NULL AND error_code IS NULL)
        OR (result = 'FAILED' AND error_code IS NOT NULL)
    )
);
CREATE INDEX ix_operation_attempt_command ON operation_attempt(command_id, attempt_number);
