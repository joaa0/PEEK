ALTER TABLE operational_exception
    DROP CONSTRAINT uq_exception_trigger;

ALTER TABLE operational_exception
    ADD COLUMN operation_command_id uuid REFERENCES operation_command(id);

CREATE UNIQUE INDEX uq_exception_command
    ON operational_exception(code, operation_command_id)
    WHERE operation_command_id IS NOT NULL;

CREATE UNIQUE INDEX uq_exception_event_without_command
    ON operational_exception(code, trigger_event_id)
    WHERE operation_command_id IS NULL;

ALTER TABLE operation_attempt
    ADD COLUMN idempotency_key varchar(200);

UPDATE operation_attempt
SET idempotency_key = 'legacy-' || id::text
WHERE idempotency_key IS NULL;

ALTER TABLE operation_attempt
    ALTER COLUMN idempotency_key SET NOT NULL;

CREATE UNIQUE INDEX uq_operation_attempt_idempotency
    ON operation_attempt(command_id, idempotency_key);

ALTER TABLE operation_command
    ADD COLUMN confirmation_occurred_at timestamptz;

UPDATE operation_command command
SET confirmation_occurred_at = event.occurred_at
FROM normalized_event event
WHERE command.status = 'CONFIRMED'
  AND command.confirmation_event_id = event.id
  AND command.confirmation_occurred_at IS NULL;

ALTER TABLE operation_command
    DROP CONSTRAINT ck_operation_confirmation;

ALTER TABLE operation_command
    ADD CONSTRAINT ck_operation_confirmation CHECK (
        (status = 'CONFIRMED' AND confirmation_event_id IS NOT NULL
            AND confirmed_at IS NOT NULL AND confirmation_occurred_at IS NOT NULL)
        OR (status <> 'CONFIRMED' AND confirmation_event_id IS NULL
            AND confirmed_at IS NULL AND confirmation_occurred_at IS NULL)
    );

CREATE TABLE product_audit (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES product(id),
    revision bigint NOT NULL,
    operation varchar(16) NOT NULL CHECK (operation IN ('CREATED', 'UPDATED')),
    sku varchar(100) NOT NULL,
    name varchar(200) NOT NULL,
    description text,
    price numeric(15, 2),
    gtin varchar(32),
    category varchar(100),
    active boolean NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT uq_product_audit_revision UNIQUE (product_id, revision)
);

CREATE INDEX ix_product_audit_product
    ON product_audit(product_id, revision);

CREATE TABLE product_channel_mapping_audit (
    id uuid PRIMARY KEY,
    mapping_id uuid NOT NULL REFERENCES product_channel_mapping(id),
    product_id uuid NOT NULL REFERENCES product(id),
    revision bigint NOT NULL,
    operation varchar(16) NOT NULL CHECK (operation IN ('CREATED', 'UPDATED')),
    channel varchar(100) NOT NULL,
    external_id varchar(200),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'INACTIVE')),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT uq_mapping_audit_revision UNIQUE (mapping_id, revision)
);

CREATE INDEX ix_mapping_audit_mapping
    ON product_channel_mapping_audit(mapping_id, revision);
