CREATE TABLE product (
    id uuid PRIMARY KEY,
    sku varchar(100) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    description text,
    price numeric(15, 2),
    gtin varchar(32),
    category varchar(100),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE product_channel_mapping (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES product(id),
    channel varchar(100) NOT NULL,
    external_id varchar(200),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_channel UNIQUE (product_id, channel),
    CONSTRAINT ck_mapping_identity CHECK ((status = 'PENDING' AND external_id IS NULL) OR (status <> 'PENDING' AND external_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_mapping_channel_external ON product_channel_mapping(channel, external_id) WHERE external_id IS NOT NULL;

CREATE TABLE normalized_event (
    id uuid PRIMARY KEY,
    source varchar(100) NOT NULL,
    external_event_id varchar(200) NOT NULL,
    event_type varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    product_id uuid REFERENCES product(id),
    sku varchar(100),
    external_product_id varchar(200),
    order_id varchar(200),
    invoice_id varchar(200),
    receipt_id varchar(200),
    movement_id varchar(200),
    quantity numeric(15, 3),
    stock_after numeric(15, 3),
    confirmed boolean NOT NULL DEFAULT false,
    metadata_json text NOT NULL,
    payload_hash varchar(64) NOT NULL,
    CONSTRAINT uq_event_source_external UNIQUE (source, external_event_id),
    CONSTRAINT ck_event_identity CHECK (sku IS NOT NULL OR product_id IS NOT NULL OR invoice_id IS NOT NULL)
);
CREATE INDEX ix_event_sku_time ON normalized_event(sku, occurred_at, received_at);
CREATE INDEX ix_event_product_time ON normalized_event(product_id, occurred_at);
CREATE INDEX ix_event_order ON normalized_event(order_id) WHERE order_id IS NOT NULL;
CREATE INDEX ix_event_receipt ON normalized_event(receipt_id) WHERE receipt_id IS NOT NULL;

CREATE TABLE demo_configuration (
    id smallint PRIMARY KEY CHECK (id = 1),
    stock_sync_timeout_seconds integer NOT NULL CHECK (stock_sync_timeout_seconds > 0),
    fiscal_timeout_seconds integer NOT NULL CHECK (fiscal_timeout_seconds > 0),
    physical_stock_tolerance numeric(15, 3) NOT NULL CHECK (physical_stock_tolerance >= 0),
    receipt_tolerance numeric(15, 3) NOT NULL CHECK (receipt_tolerance >= 0)
);
INSERT INTO demo_configuration VALUES (1, 300, 600, 1, 0);

CREATE TABLE operational_exception (
    id uuid PRIMARY KEY,
    code varchar(3) NOT NULL CHECK (code IN ('E01', 'E02', 'E03', 'E04')),
    trigger_event_id uuid NOT NULL REFERENCES normalized_event(id),
    status varchar(16) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    severity varchar(16) NOT NULL CHECK (severity IN ('CRITICAL', 'WARNING', 'INFO')),
    title varchar(200) NOT NULL,
    detected_at timestamptz NOT NULL,
    product_id uuid REFERENCES product(id),
    sku varchar(100),
    order_id varchar(200),
    expected_state text NOT NULL,
    observed_state text NOT NULL,
    rule_parameter text NOT NULL,
    impact text,
    recommendation text NOT NULL,
    resolution_note text,
    resolved_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_exception_trigger UNIQUE (code, trigger_event_id),
    CONSTRAINT ck_exception_resolution CHECK ((status = 'OPEN' AND resolved_at IS NULL AND resolution_note IS NULL) OR (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_note IS NOT NULL))
);
CREATE INDEX ix_exception_status_detected ON operational_exception(status, detected_at DESC);
CREATE INDEX ix_exception_code_detected ON operational_exception(code, detected_at DESC);

CREATE TABLE exception_evidence (
    id uuid PRIMARY KEY,
    exception_id uuid NOT NULL REFERENCES operational_exception(id),
    event_id uuid REFERENCES normalized_event(id),
    evidence_type varchar(60) NOT NULL,
    source varchar(100) NOT NULL,
    label varchar(200) NOT NULL,
    value text NOT NULL,
    occurred_at timestamptz,
    position integer NOT NULL,
    CONSTRAINT uq_exception_evidence_position UNIQUE (exception_id, position)
);
