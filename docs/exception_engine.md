# Exception Engine

## E01 recovery through deterministic reconciliation

The local MCP agent may request an existing E01 inventory retry or, with explicit
human approval, acceptance of an existing confirmed E02 checkpoint. It cannot
resolve exceptions directly. For an existing E01, EvaluationService checks the stored
confirmation against channel, external product, SKU/order, latest dispatch
time and requested expectedStock. A matching stock value and explicit engine
evaluation close E01, preserve the original failure evidence, append
RECONCILIATION evidence and persist reconciliationEventId/reconciledAt.
The evaluation includes the received confirmation and does not precede
exception detection. Unknown expected stock cannot be verified.

Manual resolution is still supported by REST for operators, but does not
prove agent success. E02 checkpoint acceptance requires human approval; E03/E04
remediation is not exposed through MCP.
See [mcp_agent.md](mcp_agent.md) for the complete policy.

## 1. Purpose

### E02 approved checkpoint reconciliation

Detection remains the pre-count comparison `abs(physical - expected) > tolerance`.
A confirmed PHYSICAL_COUNT already reanchors future expected stock in StockCalculator;
this automatic derived-state behavior does not resolve the detected discrepancy.
Only explicit human acceptance of that existing checkpoint, recorded through
the restricted MCP E02 action, makes it eligible for a separate EvaluationService
verification. The engine verifies the unchanged decision evidence, current checkpoint,
same confirmed count identity, valid reconstruction and verification deadline.
Then it preserves prior evidence and appends RECONCILIATION with the existing count
ID before reporting RESOLVED/VERIFIED. No manufactured adjustment/confirmation,
source event edits, external stock adjustment or JEV decision is involved.
Manual acknowledgment, absence of approval, stale evidence or timeout cannot
supply engine proof. See mcp_agent.md for approval, idempotency and demo details.

The Exception Engine is the central product capability.

Its responsibility is to detect when expected operational relationships stop matching observed system or physical behavior.

The engine must transform a violation into:

```text
Problem
  ↓
Evidence
  ↓
Expected state
  ↓
Observed state
  ↓
Impact/context
  ↓
Recommended action
```

---

## 2. Exception-first principle

The product should not force users to inspect all normal events.

The engine should surface situations that deserve attention.

```text
Events
  ↓
Rules / Correlation
  ↓
Everything consistent?
 ├── yes → remain silent
 └── no
      ↓
   Exception
```

Failed product-propagation attempts are durable operational evidence linked to
the canonical Product and destination. They remain visible for investigation
and retry, and may be attached to an `OperationalException` through the normal
exception/evidence path. This flow does not add a new canonical exception type
by itself; any rule that creates an exception must preserve the existing
evidence, lifecycle, and presentation contracts.

---

## 3. Detection strategy

Use two mechanisms.

### 3.1 Deterministic rules

For objective violations:

- missing expected event;
- quantity mismatch;
- timeout;
- threshold breach;
- explicit relationship failure.

Advantages:

- predictable;
- auditable;
- cheap;
- low latency;
- easy to test.

### 3.2 JEV / AI interpretation

For ambiguous situations involving multiple signals.

Possible responsibilities:

- probable cause;
- explanation;
- prioritization support;
- impact description;
- investigation path;
- action recommendation.

AI must not replace deterministic detection when the condition is objectively expressible.

---

## 4. Detection pipeline

```text
Normalized event
      ↓
Update/query operational state
      ↓
Find applicable rules
      ↓
Correlate related events
      ↓
Evaluate deterministic condition
      ↓
Violation?
 ├── no → finish
 └── yes
       ↓
    Gather evidence
       ↓
 Create exception
       ↓
 Optional JEV/AI analysis
       ↓
 Present recommendation
```

---

## 5. E01 — Stock synchronization failure

### Goal

Detect when a confirmed sale was not reflected in inventory as expected.

### Trigger

```text
SALE_CONFIRMED
```

### Expected event

```text
STOCK_UPDATED
```

### Correlation

Prefer:

```text
order_id + sku
```

Fallback correlation may be configured if source systems do not share the same identifier.

### Condition

```text
SALE_CONFIRMED exists
AND correlated STOCK_UPDATED does not exist
within configured timeout
```

### Example

```text
14:30 SALE_CONFIRMED
      order=1842
      sku=421
      qty=5

Expected by 14:35:
STOCK_UPDATED

14:35:
no matching event
```

Create:

```text
E01 / STOCK_SYNC_FAILURE
```

### Evidence

- sale event;
- order;
- SKU;
- quantity;
- sale timestamp;
- configured timeout;
- absence of correlated stock update;
- last known stock state.

### Suggested action

```text
Retry/re-send the stock update or inspect the integration responsible for inventory propagation.
```

---

## 6. E02 — Physical stock divergence

### Goal

Detect when physical count and expected/system stock disagree materially.

### Trigger

```text
PHYSICAL_COUNT
```

### Expected state

```text
physical_count ~= expected_stock
```

### Condition

```text
abs(physical_count - expected_stock) > configured_tolerance
```

### Example

```text
Expected stock: 95
Physical count: 93
Tolerance: 0
Difference: -2
```

Create:

```text
E02 / PHYSICAL_STOCK_DIVERGENCE
```

### Evidence

- physical count event;
- expected stock calculation;
- latest related sales;
- latest receipts;
- latest adjustments;
- tolerance.

### Deterministic conclusion

```text
The physical count differs from expected stock by 2 units.
```

### Possible AI/JEV interpretation

Possible hypotheses:

- loss;
- unregistered removal;
- unregistered movement;
- breakage;
- late stock update;
- counting error.

The AI must state that these are hypotheses unless supported by additional evidence.

### Suggested action

```text
Recount the item and reconcile recent movements before adjusting the system balance.
```

---

## 7. E03 — Physical exit without fiscal document

### Goal

Detect when goods physically leave but no expected fiscal event is correlated.

### Trigger

```text
PHYSICAL_EXIT
```

### Expected event

```text
INVOICE_ISSUED
```

### Condition

```text
PHYSICAL_EXIT exists
AND no correlated INVOICE_ISSUED exists
within configured process window
```

### Possible correlation

Prefer:

```text
order_id
```

Optionally:

```text
movement_id
sku
document reference
```

### Evidence

- physical exit;
- order/reference;
- SKU/quantity;
- event timestamp;
- configured time window;
- absence of fiscal event.

### Suggested action

```text
Check whether the fiscal document exists under another reference and prevent the discrepancy from being ignored.
```

---

## 8. E04 — Receipt divergence

### Goal

Detect when physically received quantity differs from inventory registration.

### Trigger

```text
GOODS_RECEIVED
```

### Expected event/state

```text
inventory registration corresponds to received quantity
```

### Condition

```text
abs(received_quantity - registered_quantity) > tolerance
```

### Evidence

- receipt event;
- SKU;
- received quantity;
- inventory update;
- registered quantity;
- timestamp;
- tolerance.

### Suggested action

```text
Reconcile the receipt before confirming the inventory quantity.
```

---

## 9. Exception object

Minimum conceptual payload:

```json
{
  "id": "exception-id",
  "code": "E01",
  "type": "STOCK_SYNC_FAILURE",
  "status": "OPEN",
  "severity": "CRITICAL",
  "detected_at": "timestamp",
  "affected": {
    "sku": "421",
    "order_id": "1842"
  },
  "expected_state": {},
  "observed_state": {},
  "evidence": [],
  "impact": null,
  "probable_cause": null,
  "recommendation": null
}
```

This is conceptual. The final implementation language/schema may differ.

---

## 10. Evidence model

Evidence should be explicit.

Examples:

```text
SALE_EVENT
MISSING_EXPECTED_EVENT
PHYSICAL_COUNT
CALCULATED_EXPECTED_STOCK
OBSERVED_STOCK
TIMEOUT
QUANTITY_DIFFERENCE
SOURCE_SNAPSHOT
```

The investigation UI should favor a timeline when useful.

Example:

```text
14:30  SALE_CONFIRMED       -5
14:31  expected stock       95
14:35  timeout reached
14:35  STOCK_UPDATED        missing
14:35  E01 created
```

---

## 11. Severity

For the hackathon, severity can remain simple.

Possible defaults:

```text
CRITICAL
WARNING
INFO
```

Example mapping:

```text
E01 stock sync failure          CRITICAL
E02 physical stock divergence   WARNING
E03 exit without fiscal         CRITICAL
E04 receipt divergence          WARNING
```

The final mapping is a product configuration decision and may be changed.

Do not build a complex severity scoring engine without a concrete need.

---

## 12. Exception lifecycle

Minimal lifecycle:

```text
OPEN
  ↓
RESOLVED
```

Optional:

```text
OPEN
  ↓
INVESTIGATING
  ↓
RESOLVED
```

`DISMISSED` may be added if false positives need to be demonstrated.

A resolution may capture:

- resolution timestamp;
- user;
- action taken;
- note.

---

## 13. AI/JEV input contract

The implemented contract is [jev_contract.md](jev_contract.md), version 1.0,
with executable `jev-input.schema.json`. It separates `facts`, `calculations`,
`expectedState`, `observedState`, `configuration` and bounded `recentEvents`.
Only the existing E02 detection record and its referenced events are projected;
current stock and later observations cannot overwrite that record. Raw metadata,
identities, source payloads and real operational data are not sent externally.

---

## 14. AI/JEV output contract

`jev-output.schema.json` requires summary, one main hypothesis with rationale
and existing evidence references, impact and recommended action. At most two
alternatives are accepted, each with contextual evidence and descending model
ranking. Unsupported causes and invented references invalidate the response.
The earlier generic `probable_causes` list was a conceptual example, not the
accepted implementation contract.

The selected TypeSafe adapter emits `TYPESAFE_CHOICE_PROBABILITY`: the
probability assigned to a hypothesis among the proposed options. The evaluation
also preserves the full distribution, choice confidence and returned Jev version.
These values do not prove a real-world cause. PEEK supplies evidence-linked
explanatory templates; Jev selects among investigation hypotheses.
`MODEL_SELF_REPORTED_RANKING` remains a legacy fixture meaning.
`HYPOTHESIS_NOT_FACT` applies to all interpretations. Neither Jev nor the
templates can alter facts, approve a checkpoint, resolve E02 or supply engine proof.

---

## 15. AI fallback

The deterministic alert must remain usable if AI fails.

Fallback:

```text
Exception type
+ deterministic explanation
+ evidence
+ static recommendation
```

AI availability must never determine whether an objective exception exists.

`JevService` returns `FALLBACK` with the recorded expected/physical values,
static impact/recommendation and no model hypothesis/confidence when disabled,
outside the fictitious demo boundary, misconfigured, missing valid context,
timed out, refused, failed or invalid. Query-time interpretation is isolated
from detection and persistence. See jev_contract.md for environment configuration.

---

## 16. Explainability requirement

The UI should allow a user to answer:

- What failed?
- What relationship was expected?
- Which evidence triggered the alert?
- What was missing or inconsistent?
- Which threshold was used?
- What is fact versus AI interpretation?
- What action is suggested?

This is a core product requirement, not an optional enhancement.

---

## 17. Anti-patterns

Do not:

- ask AI whether an explicit numeric mismatch exists;
- ask AI whether an expected event is missing when the database can answer directly;
- generate alerts without evidence;
- hide rule parameters;
- allow AI to rewrite source history;
- treat an AI hypothesis as a confirmed root cause;
- make the dashboard a generic charting/BI product;
- create dozens of rules before the four canonical exceptions work end-to-end.
