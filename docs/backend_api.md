# Backend Core API contract

This document records the implemented Backend Core surface. All timestamps are
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
immutable; stale versions return `409`.

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
external identifier remains on the stored event. No vendor payload is accepted
by this endpoint; adapters in a later milestone translate those payloads.

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
stock snapshot and the latest confirmed `INVOICE_ISSUED` event when present.
Inventory and fiscal command slots report `NOT_AVAILABLE` until their
orchestrator milestones provide attempt/confirmation history. A document is
`CONFIRMED` only when an invoice event exists; otherwise it is `UNKNOWN`.

## Exceptions

`GET /api/v1/exceptions?status=OPEN&code=E02` filters by status/code and sorts
by detection time descending. `GET /api/v1/exceptions/{id}` includes expected
and observed states, rule parameter, severity, recommendation and ordered
evidence with source and timestamp. `POST /api/v1/exceptions/{id}/resolve`
accepts `{"note":"Count verified"}` and records `resolvedAt`. Repeating the
same resolution is idempotent; a different note after resolution returns `409`.

Creation is an internal application service for deterministic rules in the
Event Engine milestone. It requires a trigger event and nonempty referenced
evidence. A unique `(code, trigger_event_id)` constraint prevents duplicate
exceptions from reprocessing. Backend Core does not run E01–E04 detection.

Error responses have `code`, `message`, and `timestamp`. The codes are
`INVALID_INPUT`, `MALFORMED_JSON`, `UNSUPPORTED_EVENT_TYPE`, `NOT_FOUND`, and
`CONFLICT`. The demo configuration row contains timeouts and tolerances for
future rule evaluation; those values are not hard-coded in rule logic.
