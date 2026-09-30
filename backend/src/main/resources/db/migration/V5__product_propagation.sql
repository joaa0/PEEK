CREATE TABLE product_propagation_command (
    id uuid PRIMARY KEY, product_id uuid NOT NULL REFERENCES product(id),
    mapping_id uuid NOT NULL REFERENCES product_channel_mapping(id),
    channel varchar(100) NOT NULL, operation varchar(10) NOT NULL,
    idempotency_key varchar(200) NOT NULL, product_version bigint NOT NULL,
    snapshot_json text NOT NULL, simulate_failure boolean NOT NULL,
    requested_at timestamptz NOT NULL, status varchar(20) NOT NULL,
    completed_at timestamptz, external_id varchar(200), version bigint NOT NULL DEFAULT 0,
    UNIQUE(channel, idempotency_key)
);
CREATE TABLE product_propagation_attempt (
    id uuid PRIMARY KEY, command_id uuid NOT NULL REFERENCES product_propagation_command(id),
    attempt_number integer NOT NULL, idempotency_key varchar(200) NOT NULL,
    simulate_failure boolean NOT NULL, dispatched_at timestamptz NOT NULL,
    responded_at timestamptz NOT NULL, result varchar(20) NOT NULL,
    external_id varchar(200), error_code varchar(100), error_message text,
    UNIQUE(command_id, attempt_number), UNIQUE(command_id, idempotency_key)
);
CREATE TABLE mock_destination_product (
    id uuid PRIMARY KEY, product_id uuid NOT NULL REFERENCES product(id),
    channel varchar(100) NOT NULL, external_id varchar(200) NOT NULL,
    snapshot_json text NOT NULL, product_version bigint NOT NULL,
    UNIQUE(product_id, channel), UNIQUE(channel, external_id)
);
