# Backend API contract

This document records the implemented Backend Core and Integrations & Event Engine surface. All timestamps are
UTC instants. JSON names use camelCase. Database migrations are owned by Flyway;
Hibernate validates the schema and does not create it.

## Product Master and mapping

`POST /api/v1/products` accepts a minimal canonical identity:

```json
{"sku":"CAM-001","name":"Demo camera","description":"Fictitious item","price":120.00,"gtin":null,"category":"Demo"}
```

It returns `201` with `id`, `version`, and timestamps. Repeating the same SKU
and representation returns `200` with the existing product; changing fields
under an existing SKU returns `409`. `GET /api/v1/products` and
`GET /api/v1/products/{id}` read products. `PUT /api/v1/products/{id}` replaces
mutable fields and requires `If-Match-Version: <version>`. The canonical SKU is
immutable; stale versions return `409`. `GET /api/v1/products/{id}/history`
returns the immutable `CREATED`/`UPDATED` snapshots recorded for that product.

Example `201`/`200` response:

```json
{"id":"<product UUID>","sku":"CAM-001","name":"Demo camera","description":"Fictitious item","price":120.00,"gtin":null,"category":"Demo","active":true,"createdAt":"2026-01-01T12:00:00Z","updatedAt":"2026-01-01T12:00:00Z","version":0}
```

`POST /api/v1/products/{id}/mappings` accepts:

```json
{"channel":"demo-inventory","externalId":"EXT-CAM-001","status":"ACTIVE"}
```

Mappings are unique for one product/channel and one channel/external ID.
`PENDING` requires a null external ID; `ACTIVE` and `INACTIVE` require one.
An identical create returns `200`; a conflicting mapping returns `409`.
`GET /api/v1/products/{id}/mappings` lists mappings.
`PUT /api/v1/product-mappings/{id}` requires `If-Match-Version` and can change
the external ID or status while retaining the same channel. Resolve an active
mapping with `GET /api/v1/product-mappings/resolve?channel=demo-inventory&externalId=EXT-CAM-001`.
An absent mapping returns `404`; an inactive mapping returns `409`. The unique
database index prevents an ambiguous active identity.
`GET /api/v1/product-mappings/{id}/history` returns the mapping audit snapshots.
Whitespace-only external IDs are normalized to null before applying status
invariants.

Example `201`/`200` mapping response:

```json
{"id":"<mapping UUID>","productId":"<product UUID>","channel":"demo-inventory","externalId":"EXT-CAM-001","status":"ACTIVE","createdAt":"2026-01-01T12:01:00Z","updatedAt":"2026-01-01T12:01:00Z","version":0}
```

## Canonical event ingestion

`POST /api/v1/events` accepts only the seven types in `docs/domain_model.md`.
Example:

```json
{"source":"demo-sales","externalEventId":"sale-1842","type":"SALE_CONFIRMED","occurredAt":"2026-01-01T14:30:00Z","sku":"CAM-001","orderId":"1842","quantity":5,"metadata":{}}
```

The server assigns a stable internal `id` and `receivedAt`. Events are
append-only. The unique identity is `(source, externalEventId)`. Repeating the
same payload returns `200` with the original event and receipt time. Reusing
the identity for different content returns `409`. New events return `201` and
`Location: /api/v1/events/{id}`. `GET /api/v1/events/{id}` reads the stored
observation. Invalid fields return `400`; an unknown event type returns `422`.
All event quantities use numeric values, not local-time or formatted strings.
`PHYSICAL_COUNT` requires an explicit `confirmed` boolean.

An event may identify a product by canonical `sku`/`productId`, or by
`source` and `externalProductId`; the latter resolves through an active
ProductChannelMapping. Conflicting identities return `409`. The original
external identifier remains on the stored event. Vendor-shaped mock payloads
are accepted only by the mock adapter endpoints below.

## Stock and operational context

`GET /api/v1/stocks/{sku}` returns `expectedStock`, `systemStock`,
`physicalStock`, baseline/checkpoint event IDs, timestamps, and `usedEventIds`.
The first chronological `STOCK_UPDATED` with `stockAfter` seeds the expected
balance. Later stock updates change only the observed `systemStock`. Receipts
add, sales subtract, and stock adjustments apply their signed quantity to the
expected balance. A confirmed physical count records `physicalStock` and
reanchors the expected balance for subsequent events; earlier events remain
stored. Without a baseline or confirmed count, `expectedStock` is null rather
than an invented zero. An unconfirmed count does not change the calculation.

`GET /api/v1/products/{id}/context` combines canonical product/mappings, the
stock snapshot, related exceptions, and command state.
Inventory and fiscal command slots report the latest command status and attempt
history, command ID, channel, request/deadline timestamps and confirmation time,
or `NOT_AVAILABLE` when no command exists. A fiscal document is
`CONFIRMED` only when an `INVOICE_ISSUED` event has confirmed that product's
correlated fiscal command. An unrelated invoice event cannot change the
product context. The fiscal view exposes command, evidence event, external
document, confirmation receipt time, and document occurrence time.

## Exceptions

`GET /api/v1/exceptions?status=OPEN&code=E02` filters by status/code and sorts
by detection time descending. `GET /api/v1/exceptions/{id}` includes expected
and observed states, rule parameter, severity, recommendation and ordered
evidence with source and timestamp. `POST /api/v1/exceptions/{id}/resolve`
accepts `{"note":"Count verified"}` and records `resolvedAt`. Repeating the
same resolution is idempotent; a different note after resolution returns `409`.

Creation is an internal application service for deterministic E01–E04 rules.
It requires a trigger event and nonempty referenced evidence. E01/E03 identity
is `(code, operation_command_id)`, so independent channel commands from one
trigger retain independent exceptions. E02/E04 use `(code, trigger_event_id)`.
`POST /api/v1/evaluations` runs the evaluator
with `{"asOf":"2026-01-01T14:35:00Z"}` and returns `created`,
`alreadyPresent`, exception IDs, and explicit missing-correlation/baseline
issues. Repeating a run does not duplicate exceptions. E01 and E03 require a
PEEKio command and an elapsed deadline; a lone sale or exit is not treated as
a failed dispatch. Their evidence contains the command, mapping/channel,
correlation keys, deadline, expected state, every dispatch attempt and any
mismatched candidate confirmations. E02 compares a confirmed count against the expected stock
immediately before that count; E04 sums inventory registrations with the same
receipt ID and SKU. Timeouts and tolerances come from `demo_configuration`.

## Mock adapters and orchestrators

The following endpoints translate distinct, fictitious external formats into
canonical append-only events. They return `201` for a new event or `200` for
an identical replay, and expose the canonical event body. Unknown physical
notice kinds return `422`; invalid fields return `400`; absent/inactive product
mappings return `404`/`409` as for direct event ingestion.

| Endpoint | Mock payload fields | Canonical event |
| --- | --- | --- |
| `POST /api/v1/mock/sales` | `messageId`, `channel`, `saleNumber`, `itemId`, `units`, `happenedAt` | `SALE_CONFIRMED` |
| `POST /api/v1/mock/inventory` | `changeId`, `system`, `warehouseSku`, `orderRef`, `receiptRef`, `registeredUnits`, `onHand`, `occurredUtc` | `STOCK_UPDATED` |
| `POST /api/v1/mock/fiscal` | `notificationId`, `system`, `documentNumber`, `orderReference`, `movementReference`, `itemCode`, `pieces`, `issuedAt` | `INVOICE_ISSUED` |
| `POST /api/v1/mock/physical` | `ticketId`, `source`, `kind`, `sku`, `order`, `receipt`, `movement`, `units`, `countConfirmed`, `registeredAt` | `GOODS_RECEIVED`, `PHYSICAL_COUNT`, `STOCK_ADJUSTED` or `PHYSICAL_EXIT` |

`kind` is `RECEIPT`, `COUNT`, `ADJUSTMENT`, or `EXIT`. The sample JSON bodies
live in `backend/src/test/resources/fixtures/`; they contain no real account,
company, product, or fiscal data. Original source references are retained in
canonical fields and metadata. Business rules never read vendor-specific JSON.

Create an outbound simulated command with `POST /api/v1/commands/inventory-sync`
from a `SALE_CONFIRMED` event, or `POST /api/v1/commands/fiscal` from a
`PHYSICAL_EXIT` event:

```json
{"triggerEventId":"<event UUID>","channel":"demo-inventory","idempotencyKey":"sync-1842","simulateFailure":false}
```

The target channel must have an active Product Master mapping. A new command
returns `201`; the same kind/idempotency key, trigger and channel returns
`200` without another dispatch; conflicting reuse returns `409`. The response
includes requested quantity, expected stock when relevant, deadline, status,
and immutable attempt history. Inventory and fiscal dispatches cross separate
outbound adapter boundaries. `simulateFailure` is a test-only switch on those
mock adapters. `ACCEPTED` means dispatch acceptance, never stock or
document confirmation. Confirmation requires a later canonical event from the
target channel with matching external product ID, SKU and order ID (inventory)
or the configured fiscal reference. An invoice ID is recorded only from
`INVOICE_ISSUED`; the service does not issue a real fiscal document.

`GET /api/v1/commands/{id}` reads status and attempts, including the immutable
idempotency key for each attempt.
`POST /api/v1/commands/{id}/retry` accepts
`{"idempotencyKey":"retry-sync-1842-1","simulateFailure":false}` only for
`FAILED` or `TIMED_OUT` commands and appends another immutable attempt. Reusing
the same retry key returns the already recorded result without dispatching or
adding an attempt; a different retry key is rejected once the command is no
longer retryable.
The fiscal key is `demo_configuration.fiscal_correlation_key`, either
`ORDER_ID_AND_SKU` (default) or `MOVEMENT_ID_AND_SKU`. Other settings in that
single configuration row are `stock_sync_timeout_seconds`,
`fiscal_timeout_seconds`, `physical_stock_tolerance`, and `receipt_tolerance`.
All evaluations take an explicit UTC instant. Late confirmations remain in the
audit trail but do not erase an exception for a missed process window.

Error responses have `code`, `message`, and `timestamp`. The codes are
`INVALID_INPUT`, `MALFORMED_JSON`, `MISSING_HEADER`, `UNSUPPORTED_EVENT_TYPE`,
`UNSUPPORTED_EXTERNAL_PAYLOAD`, `NOT_FOUND`, and `CONFLICT`.

## Demo reset

`POST /api/v1/demo/reset` exists only when `peek.demo.reset-enabled=true`; the
provided `demo` Spring profile enables it. It is a local demo utility, not a
production data-management endpoint. Request:

```json
{"runId":"DEMO-1720000000000","confirmation":"RESET_DEMO"}
```

The `runId` must use the `DEMO-*` namespace (or the legacy `QA-*` namespace).
Reset selects only the fictitious products `CAM-<runId>`, `SKU-E02-<runId>`,
and `SKU-E04-<runId>` with category `Demo`. It deletes events linked to those
product IDs, their commands/attempts, exceptions/evidence, and product/mapping
audit in referential order inside one transaction. External event IDs are not
used to infer ownership: one run ID may be a prefix of another. Every demo
fixture must first create its product so its events carry that product ID.
Other products/events and the `demo_configuration` row are not modified.
Repeating the same reset succeeds with zero deleted rows.

The response reports the deleted count for each resource type. The executable
fixtures in `postman/PEEK-Manual-QA.postman_collection.json` call this endpoint
before seeding a run and twice after the scenarios. E01-E04 source-shaped
payloads enter through `/api/v1/mock/*`, so reset/replay does not bypass
adapters or reconciliation. Normal inventory and fiscal confirmation are
evaluated after their deadlines and checked for the absence of E01/E03.
E01/E03 use an explicit evaluation `asOf` after the command deadline, providing
controllable demo time without changing the application clock or waiting in
real time.
