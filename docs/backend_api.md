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

For E01, a later matching recovery can now resolve the existing exception
through EvaluationService, preserving that missed-window audit and adding
reconciliation evidence. The detail includes reconciliationEventId and
reconciledAt. Manual REST resolution does not set this proof. E03's previous
late-confirmation behavior is retained. The optional local MCP profile and its
closed semantic tool contract are documented in [mcp_agent.md](mcp_agent.md).

Error responses have `code`, `message`, and `timestamp`. The codes are
`INVALID_INPUT`, `MALFORMED_JSON`, `MISSING_HEADER`, `UNSUPPORTED_EVENT_TYPE`,
`UNSUPPORTED_EXTERNAL_PAYLOAD`, `NOT_FOUND`, and `CONFLICT`.

## Integrated investigation context

`GET /api/v1/exceptions/{id}/context` returns the persisted `exception`, its
canonical `trigger`, a read-only `order` derived only from SALE_CONFIRMED (or
null), `stockAtDetection`, `currentStock`, `systemSnapshot` and
`physicalCheckpoint`. The stock reconstruction uses events up to the detection
instant. E02 reconstructs the expected balance immediately BEFORE its triggering
confirmed count, then shows that count as the observed physical balance. The
current snapshot includes its checkpoint and subsequent movements. Persisted
exception evidence remains the original detection record; later observations
and retries do not overwrite it. The frontend resolves an external product
identity through the existing mapping endpoint, keeping absent/inactive
mappings explicit.

## Product registration propagation

`POST /api/v1/products/{id}/propagations` accepts:

```json
{"idempotencyKey":"product-operation-1","productVersion":0,"targets":[{"channel":"ERP","simulateFailure":false},{"channel":"MERCADO_LIVRE","simulateFailure":false},{"channel":"SHOPEE","simulateFailure":true}]}
```

Supported simulated destinations are ERP, MERCADO_LIVRE and SHOPEE. At least
one unique destination is required. The service persists a separate command
and immutable product snapshot/version for each target; it creates a PENDING
mapping when absent, dispatches through the simulated destination adapter and
records each result. A success returns a stable external ID and activates the
mapping; failure keeps a normalized error code/message and a FAILED command.
CREATE/UPDATE is selected from the existing mapping identity. Responses are
200 arrays with command/product/mapping IDs, channel, operation, version,
request/completion timestamps, status, external ID, attempts and evidence.

The unique identity is `(channel, idempotencyKey)`. An identical replay does
not dispatch again; reuse for another product/version/failure choice returns
409. Product-level locking serializes dispatches and retries; target results
can differ within one operation. The simulated external effect is a durable
`mock_destination_product` row unique by product/destination, rather than a
second create per retry. No stock/fiscal event is fabricated by registration.

`GET /api/v1/products/{id}/propagations` lists history.
`GET /api/v1/product-propagations/{id}` reads one command.
`POST /api/v1/product-propagations/{id}/retry` accepts
`{"idempotencyKey":"product-retry-1","simulateFailure":false}`. Only FAILED
commands are eligible. Replaying the same attempt key does not append or
redispatch; conflicting reuse returns 409. A changed product version requires
a new propagation, avoiding an old snapshot overwriting the updated product.
Inactive mappings are rejected explicitly.

Propagation evidence uses `ExceptionService.EvidenceView` and the same UI
investigation timeline as E01–E04, linking the canonical product, command,
version, destination and immutable results. Failure is investigated on the
product view; it does not create a new exception family or bypass the event
pipeline. Future exception attachment must use the existing evidence/lifecycle
contracts. V5 adds propagation commands, attempts and simulated destination
records; existing canonical event types and deterministic rules are unchanged.

## Demo clock and reset

With `--spring.profiles.active=demo`, reset is enabled at
`POST /api/v1/demo/reset` with `{"runId":"QA-EXAMPLE-123456","confirmation":"RESET_DEMO"}`.
Only category Demo products with exact SKUs `CAM-<runId>`, `SKU-E02-<runId>` and
`SKU-E04-<runId>` are owned by that run. Linked exceptions/evidence, events,
stock/fiscal attempts/commands and associated agent_action_execution audit, product propagation attempts/commands,
simulated destination effects, mappings and audit records are removed in one
transaction. Unrelated products/configuration are preserved. A repeated reset
returns zero `totalDeleted`. The response includes per-table deletion counts.
Outside demo, the reset route is unavailable.

The optional `--peek.demo.clock=2026-01-01T12:00:00Z` freezes backend time only
under the demo profile. Production/default uses the normal UTC clock. Browser
fixtures use this clock and explicit evaluation instants; see
`frontend_validation.md` for the ordered execution commands.


## Guarded inventory correction via local MCP

There is no generic stock-edit REST endpoint. In the enabled local MCP profile,
peek_get_operational_context adds correctionCandidates with exception/product/
mapping/channel/external identity, physicalCountEventId, independent expectedStock,
physicalStock, target-channel systemStock, tolerance, targetStock,
decisionFingerprint, eligible and reason. Noneligible candidates have targetStock
null. E03/E04 return no correction candidates.

Call peek_correct_inventory_stock with exactly:

```json
{
  "exceptionId": "<OPEN E01/E02 UUID>",
  "mappingId": "<reviewed active target mapping UUID>",
  "idempotencyKey": "stock-correction-1",
  "decisionFingerprint": "<64 lowercase hex characters from reviewed candidate>"
}
```

Unknown arguments, including quantity/targetStock, are protocol errors. Changed,
ambiguous, duplicate or inactive contexts are tool errors without dispatch.
Successful dispatch returns command, actionId, executionStatus=SUCCEEDED,
verificationStatus=PENDING_VERIFICATION and replayed=false. The command kind is
INVENTORY_CORRECTION; requestedQuantity/expectedStock equal the backend target.
The same key/fingerprint returns the existing action/command with replayed=true.
A different key cannot duplicate that checkpoint/mapping. Generic create/retry
cannot produce or retry this command kind.

The simulated outbound adapter records the effect, but does not auto-confirm it.
An independent mock inventory notice or normalized STOCK_UPDATED must use the
command channel, externalProductId, generated orderId and exact stockAfter, with
fresh timestamps. Command association and event append commit atomically.
peek_get_command_status exposes attempts and shared action audit (including
mappingId, physicalCountEventId, targetStock and decisionFingerprint).
peek_get_exception_status exposes final proof after separate evaluation.
Only EvaluationService can move the action to VERIFIED. Missing confirmation
remains pending until timeout; failure, stale evidence or mismatch never claims
successful reconciliation. V7 is required for schema/semantic constraints and
the simulated destination-effect table; see mcp_agent.md for complete policy.
