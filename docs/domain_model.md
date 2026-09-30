# Domain Model

## 1. Core concepts

The initial domain is built around:

- `SourceSystem`
- `NormalizedEvent`
- `OperationalState`
- `ExpectedRelationship`
- `ProcessTemplate`
- `CustomerConfiguration`
- `ReconciliationRule`
- `OperationalException`
- `Evidence`
- `Recommendation`

The model should remain small enough for the hackathon while preserving clear domain boundaries.

The MVP also includes a minimal canonical product identity and channel/source
mapping, plus inventory and fiscal orchestration command state. These concepts
support correlation and operational execution; they do not make PEEKio the
system of record for product content, inventory, or fiscal documents.

## 1.1 Product and ProductChannelMapping

`Product` is the canonical product record created and maintained in PEEKio. It
has a stable internal ID and the minimum identity needed by the operational
flow. Depending on the flow, it may include an internal SKU, name, basic
description, price, optional EAN/GTIN, basic category, and required fiscal
fields. This is not a complete catalog/PIM record.

`ProductChannelMapping` associates a Product with one destination system and
the external product identifier assigned by that destination. A pending mapping
may be persisted before the first propagation; `external_id` is recorded when a
destination returns it. A source/destination identifier must not map
ambiguously to multiple active Products.

Events received from systems also resolve to Product through this mapping.
Product registration in PEEKio and event identity resolution therefore share
the same canonical identity without moving system-of-record ownership for
transactions to PEEKio.

## 1.2 ProductPropagationCommand and ProductPropagationAttempt

Saving a Product with selected destinations creates a create/update command for
each destination. Each destination has its own command status and immutable
attempt history, so one destination may succeed while another fails. An
attempt records at minimum the target, operation, request/correlation key,
idempotency key, start and completion timestamps, result status, returned
`external_id` when present, and failure evidence when not successful (for
example, a normalized error code/message and safe response metadata).

Each target's latest status is derived from its command and attempts; an
aggregate operation must not hide partial success or failure by destination.

Retry/reprocessing creates a new attempt linked to the same Product and target;
it does not delete or overwrite earlier attempt history. Successful results
upsert the destination's `ProductChannelMapping`. Destination adapters are
simulated in the hackathon. A failed attempt remains available to the existing
investigation/exception presentation without adding a canonical exception
family by implication.

Mappings must reject conflicting active identities for the same source/channel
identifier. Missing or inactive mappings remain explicit resolution states and
must not be silently guessed. Source-specific payloads stay in adapters.

## 1.3 Inventory and Fiscal OrchestrationCommand and Attempt

An orchestration command records requested work, its canonical product and
external mapping, correlation key, idempotency key, requested state/quantity
when relevant, status, and timestamps. Each dispatch/retry creates an auditable
attempt. External confirmation is represented separately from command dispatch
and linked to the relevant normalized event or external document reference.

Retry/reprocessing reuses the business correlation and preserves prior
attempts. Duplicate idempotency keys must not duplicate external effects.
Command status alone is not evidence that the external system completed work.

### External-agent audit and proof

AgentActionExecution records a restricted MCP action separately from Attempt:
exception/command IDs, agent/tool/action, normalized idempotency key, start/end,
execution and verification statuses, and safe input/output summaries.
Its logical identity is (command_id, tool_name, idempotency_key).

For E01, reconciliation_event_id and reconciled_at on OperationalException
refer to the engine-validated STOCK_UPDATED evidence. Manual resolution does
not populate these fields. VERIFIED requires this proof as well as the linked
confirmed command; dispatch acceptance remains PENDING_VERIFICATION.

---

## 2. SourceSystem

Represents the system or operational origin that produced a source event.

Examples:

```text
COMMERCIAL_SYSTEM
INVENTORY_SYSTEM
FISCAL_SYSTEM
PHYSICAL_OPERATION
MOCK_MARKETPLACE
```

Vendor-specific names should normally remain in adapters or source configuration.

Possible fields:

```text
id
name
type
external_system_key
enabled
metadata
```

---

## 3. NormalizedEvent

A normalized event is an immutable internal representation of something that happened or was reported.

Minimum conceptual structure:

```text
NormalizedEvent
├── event_id
├── type
├── source
├── occurred_at
├── received_at
├── correlation
│   ├── sku?
│   ├── order_id?
│   ├── invoice_id?
│   ├── receipt_id?
│   └── movement_id?
├── quantity?
└── metadata
```

### 3.1 Event rules

- `event_id` must be stable.
- Events should be append-only.
- Duplicate delivery must not create duplicate business effects.
- Original source identifiers should be preserved in metadata or correlation data.
- Vendor payloads must be translated before domain processing.

---

## 4. Initial event vocabulary

### SALE_CONFIRMED

A sale/order quantity has been confirmed by a commercial source.

Typical fields:

```text
sku
order_id
quantity
occurred_at
```

### STOCK_UPDATED

An inventory source reports an updated quantity or inventory delta.

Typical fields:

```text
sku
quantity
stock_after?
movement_id?
occurred_at
```

### INVOICE_ISSUED

A fiscal document was issued.

Typical fields:

```text
invoice_id
order_id?
sku?
quantity?
occurred_at
```

### GOODS_RECEIVED

Physical or operational receipt of goods.

Typical fields:

```text
receipt_id
sku
quantity
occurred_at
```

### PHYSICAL_COUNT

A physical inventory count was performed.

Typical fields:

```text
sku
quantity
location?
occurred_at
```

### STOCK_ADJUSTED

A manual or formal inventory adjustment occurred.

Typical fields:

```text
sku
quantity_delta
reason?
occurred_at
```

### PHYSICAL_EXIT

Goods physically left the relevant operation/location.

Typical fields:

```text
sku
quantity
order_id?
movement_id?
occurred_at
```

---

## 5. Correlation

Correlation links events that participate in the same operational relationship.

Possible keys:

- SKU;
- order ID;
- invoice ID;
- receipt ID;
- movement ID;
- location;
- customer-specific reference.

Correlation should not depend on one universal key.

Rules should define which keys are relevant.

Example:

```text
SALE_CONFIRMED(order_id=1842, sku=421)
        ↓ correlate by order_id + sku
STOCK_UPDATED(order_id=1842, sku=421)
```

If a source does not provide a direct correlation key, process configuration may define an alternative strategy.

---

## 6. OperationalState

Operational state represents derived information inferred from known events.

It is not the same as a source system's current snapshot.

For stock, an initial simplified model may be:

```text
expected_system_stock =
    previous_system_stock
  + confirmed_receipts
  - confirmed_sales
  - registered_losses
  + manual_adjustments
```

Example:

```text
Previous stock:        100
Confirmed receipt:     +20
Confirmed sales:       -15
Registered breakage:    -2
--------------------------------
Expected stock:         103
Observed ERP stock:     108
Difference:              +5
```

The system can detect the divergence without immediately knowing its root cause.

---

## 7. ExpectedRelationship

Defines an operational relation the system expects to hold.

Examples:

```text
SALE_CONFIRMED -> STOCK_UPDATED
PHYSICAL_EXIT -> INVOICE_ISSUED
GOODS_RECEIVED -> STOCK_UPDATED
PHYSICAL_COUNT ~= EXPECTED_STOCK
```

An expected relationship may include:

```text
trigger_event_type
expected_event_type
correlation_fields
timeout
tolerance
severity
```

---

## 8. ProcessTemplate

A process template describes a reusable expected flow.

Example:

```text
Sale
  ↓
Stock reservation/update
  ↓
Invoice
  ↓
Physical exit
  ↓
Final stock effect
```

Conceptually:

```text
SALE_CONFIRMED
    ↓
STOCK_RESERVED / STOCK_UPDATED
    ↓
INVOICE_ISSUED
    ↓
PHYSICAL_EXIT
```

The MVP does not need to implement a complete workflow engine.

Templates exist to avoid embedding each customer's sequence directly in code.

---

## 9. CustomerConfiguration

Separates reusable product logic from company-specific parameters.

Potential configuration:

```text
customer_id
active_process_template
source_systems
correlation_fields
timeouts
tolerances
severity_overrides
enabled_rules
```

Examples:

```text
stock_sync_timeout = 5 minutes
physical_stock_tolerance = 1 unit
physical_exit_fiscal_timeout = 10 minutes
```

The MVP may use one fixed demo configuration, but the internal design should not make those values impossible to configure later.

---

## 10. ReconciliationRule

Represents a deterministic rule that compares expected and observed operational behavior.

Conceptual structure:

```text
ReconciliationRule
├── code
├── trigger
├── expected_relation
├── correlation_strategy
├── timeout/tolerance
├── severity
└── exception_type
```

Example:

```text
Rule code:
E01

Trigger:
SALE_CONFIRMED

Expected:
STOCK_UPDATED

Condition:
No correlated STOCK_UPDATED within 5 minutes

Result:
STOCK_SYNC_FAILURE
```

---

## 11. OperationalException

Represents a detected operational inconsistency.

Conceptual structure:

```text
OperationalException
├── id
├── code
├── status
├── severity
├── title
├── detected_at
├── affected_entities
├── expected_state
├── observed_state
├── evidence[]
├── probable_cause?
├── impact?
├── recommendation?
└── resolution?
```

### 11.1 Suggested statuses

```text
OPEN
INVESTIGATING
RESOLVED
DISMISSED
```

For a very small MVP, `OPEN` and `RESOLVED` may be sufficient.

### 11.2 Suggested severities

```text
CRITICAL
WARNING
INFO
```

Do not over-engineer severity scoring for the hackathon.

---

## 12. Evidence

Evidence is the basis for the exception.

Possible evidence types:

- normalized source event;
- missing expected event;
- calculated state;
- source-reported state;
- physical count;
- timestamp difference;
- quantity difference;
- configuration threshold.

Conceptual structure:

```text
Evidence
├── type
├── source
├── reference_id?
├── label
├── value
├── occurred_at?
└── metadata?
```

Evidence should be auditable and understandable by a human.

---

## 13. Recommendation

A recommendation is the next useful action suggested to the user.

Examples:

### E01

```text
Re-send the stock update or inspect the integration responsible for inventory propagation.
```

### E02

```text
Perform a recount and adjust the system balance if the divergence is confirmed.
```

### E03

```text
Check whether the fiscal document was issued under another reference or block further processing until the discrepancy is reviewed.
```

### E04

```text
Reconcile the receipt quantity before accepting the inventory update.
```

A recommendation is not the same as an automated action.

The MVP should not let AI autonomously execute critical operational changes.

---

## 14. Exception types

### E01 — STOCK_SYNC_FAILURE

Trigger:

```text
SALE_CONFIRMED
```

Expected:

```text
STOCK_UPDATED
```

Condition:

```text
missing correlated stock update after configured timeout
```

### E02 — PHYSICAL_STOCK_DIVERGENCE

Trigger:

```text
PHYSICAL_COUNT
```

Expected:

```text
physical quantity ~= expected quantity
```

Condition:

```text
abs(physical - expected) > tolerance
```

### E03 — PHYSICAL_EXIT_WITHOUT_FISCAL

Trigger:

```text
PHYSICAL_EXIT
```

Expected:

```text
INVOICE_ISSUED
```

Condition:

```text
no correlated fiscal document in the expected process window
```

### E04 — RECEIPT_DIVERGENCE

Trigger:

```text
GOODS_RECEIVED
```

Expected:

```text
registered inventory quantity corresponds to receipt quantity
```

Condition:

```text
received quantity != recorded quantity beyond tolerance
```

---

## 15. Domain invariants

1. Raw vendor payloads must not leak into reconciliation rules.
2. Events should remain traceable to their source.
3. Historical events should not be silently overwritten.
4. Operational exceptions must contain evidence.
5. AI explanation must not become the only evidence.
6. Objective comparisons should remain deterministic.
7. Customer-specific parameters should be configurable.
8. An operational exception should not crash event processing.
9. Duplicate event delivery should not duplicate operational effects.
10. Derived state must be distinguishable from source-reported state.
