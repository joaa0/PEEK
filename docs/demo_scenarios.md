# Demo Scenarios

## Local external-agent E01 demo

The reproducible prepare/watch driver, MCP configuration, recommended Codex
instruction and VERIFIED/PENDING_VERIFICATION/FAILED checks are documented in
[mcp_agent.md](mcp_agent.md). The driver feeds the existing mock inventory
endpoint; Codex uses only semantic tools. Confirmation plus the deterministic
engine, rather than a tool's return value, determines the final outcome.

## 1. Purpose

The demo must prove the product thesis through visible operational inconsistencies.

The preferred structure is:

1. show a normal state;
2. introduce one deliberate process violation;
3. let the platform detect it;
4. open the resulting exception;
5. show evidence;
6. show expected versus observed state;
7. show recommendation;
8. optionally show AI/JEV interpretation.

Avoid a demo based only on static dashboard screens.

The expanded demo must make operational execution observable: show a command
initiated by PEEKio, the external/simulated system response, and the resulting
confirmation or exception. A submitted command is not shown as successful
until a correlated confirmation is received. Include a controlled retry with
its attempt history.

---

## 2. Demo Scenario A — Stock synchronization failure

### Initial state

```text
SKU: A
System stock: 100
Expected stock: 100
```

### Step 1 — Sale occurs

Inject:

```text
SALE_CONFIRMED
sku=A
order_id=1842
quantity=5
```

### Expected result

```text
Expected stock = 95
```

### Step 2 — PEEKio dispatches inventory synchronization

Dispatch the stock command using the canonical product and its channel
mapping. The simulated inventory adapter records the request.

### Step 3 — Deliberately omit confirmation

Do **not** emit the expected:

```text
STOCK_UPDATED
```

### Step 4 — Trigger timeout

After the configured interval:

```text
E01 / STOCK_SYNC_FAILURE
```

should be created.

The investigation shows the command, attempt, timeout, expected and observed
stock, and the missing confirmation. A retry is explicit and produces another
traceable attempt.

### Investigation view

Show:

```text
Sale registered:        -5
Previous stock:         100
Expected stock:          95
Observed stock:         100
Stock update event:  missing
```

### Suggested action

```text
Re-send the inventory update or inspect the integration.
```

### What this scenario proves

- event ingestion;
- normalization;
- expected state;
- timeout;
- missing-event detection;
- evidence;
- exception creation;
- recommendation.

---

## 3. Demo Scenario B — Physical versus system divergence

This scenario can immediately follow Scenario A.

### Initial derived state

```text
Expected stock: 95
```

### Step 1 — Perform a physical count

Inject:

```text
PHYSICAL_COUNT
sku=A
quantity=93
```

### Step 2 — Compare

```text
Expected: 95
Physical: 93
Difference: -2
```

### Result

```text
E02 / PHYSICAL_STOCK_DIVERGENCE
```

### Investigation view

Evidence may show:

- last sale;
- expected stock;
- physical count;
- difference;
- latest stock update;
- latest adjustment.

### JEV/AI role

AI may suggest hypotheses such as:

- unregistered loss;
- unregistered movement;
- breakage;
- counting error.

It must not claim one cause is confirmed without evidence.

### Suggested action

```text
Recount and inspect recent stock movements.
```

### What this scenario proves

- physical evidence;
- derived state versus observed state;
- quantity mismatch;
- AI interpretation of an ambiguous cause.

---

## 4. Demo Scenario C — Physical exit without fiscal document

### Step 1 — Simulate physical exit

Inject:

```text
PHYSICAL_EXIT
sku=B
order_id=2201
quantity=2
```

### Step 2 — Omit fiscal event

Do not create:

```text
INVOICE_ISSUED
```

for the correlated order.

### Step 3 — Trigger configured process window

Result:

```text
E03 / PHYSICAL_EXIT_WITHOUT_FISCAL
```

### Investigation view

Show:

```text
Physical exit: found
Order: 2201
SKU: B
Quantity: 2
Fiscal document: missing
```

### Suggested action

```text
Verify fiscal issuance or the document reference before the discrepancy is ignored.
```

### What this scenario proves

- cross-domain reconciliation;
- physical + fiscal correlation;
- missing expected event;
- operational/fiscal traceability.

---

## 5. Demo Scenario D — Divergent receipt

### Step 1 — Physical receipt

Inject:

```text
GOODS_RECEIVED
receipt_id=R100
sku=C
quantity=50
```

### Step 2 — Inventory registration

Inject:

```text
STOCK_UPDATED
receipt_id=R100
sku=C
quantity=47
```

### Step 3 — Reconcile

Result:

```text
Expected registered quantity: 50
Observed registered quantity: 47
Difference: -3
```

Create:

```text
E04 / RECEIPT_DIVERGENCE
```

### Suggested action

```text
Reconcile the receipt quantity before accepting the inventory registration.
```

### What this scenario proves

- receipt/inventory reconciliation;
- quantity comparison;
- evidence aggregation.

---

## 6. Preferred hackathon demo order

Recommended live sequence:

```text
1. Dashboard initially clean
2. Show source/event simulator
3. Send normal event
4. Send SALE_CONFIRMED
5. Omit stock update
6. E01 appears
7. Open E01
8. Show evidence
9. Resolve/acknowledge
10. Send PHYSICAL_COUNT with divergence
11. E02 appears
12. Open AI/JEV explanation
```

This provides both:

- a deterministic exception;
- an ambiguous situation where AI adds value.

---

## 7. Demo UI checklist

### Exception list

Show:

- severity;
- exception type;
- affected SKU/order;
- detection time;
- status.

### Investigation view

Show:

- human-readable problem;
- expected state;
- observed state;
- evidence timeline;
- difference/timeout;
- recommendation;
- AI/JEV analysis when available.

### Optional event simulator

Useful controls:

- select event type;
- SKU;
- quantity;
- order/reference;
- send event;
- omit expected follow-up event.

The simulator should support the demo, not become part of the product story.

---

## 8. Demo script — concise version

### Opening

> The company already has systems for sales, inventory and fiscal operations. The problem is that those systems may stop agreeing with one another.

### Normal state

Show no active exception.

### Violation

Inject a sale of 5 units and deliberately prevent the inventory update.

### Detection

> The platform knows that this sale should have produced an inventory event within the configured window.

Show `E01`.

### Evidence

> Instead of a generic alert, the manager sees what happened, what was expected and what is missing.

Open investigation.

### Second violation

Inject a physical count lower than expected.

### AI/JEV

> The divergence itself is deterministic. AI is only used to interpret the recent signals and suggest possible causes and the next investigation step.

### Closing

> The platform does not replace the ERP. It observes the operation and detects when reality stops matching what the systems believe happened.

---

## 9. Demo acceptance criteria

The MVP is demo-ready when:

- all four exception types can be reproduced;
- E01 can be demonstrated end-to-end;
- E02 can be demonstrated end-to-end;
- exceptions contain evidence;
- expected and observed state are visibly different;
- no alert depends exclusively on AI;
- AI failure does not break the demo;
- exception lifecycle works at least at `OPEN -> RESOLVED`;
- demo data can be reset quickly;
- centralized product registration can be propagated to multiple simulated
  destinations, with independent outcomes and a traceable retry after failure.

## 10. Demo Scenario E — Central product registration and propagation

Create Product `CAM-001` once in PEEKio with the minimum required product
fields, then select ERP, Mercado Livre, and Shopee as destinations. All three
destinations use simulated adapters.

Expected sequence:

1. ERP succeeds and returns a simulated `external_id`; its
   `ProductChannelMapping` is saved.
2. Mercado Livre succeeds independently and saves its own simulated
   `external_id` mapping.
3. Shopee fails; its status and attempt evidence remain visible alongside the
   two successful destinations.
4. The operator retries the Shopee destination without deleting its failed
   attempt.
5. Shopee succeeds and its `ProductChannelMapping` receives the returned
   simulated `external_id`.

This scenario demonstrates centralized registration and controlled outbound
orchestration. It does not require real ERP, marketplace, or e-commerce APIs.

## 11. Automated integrated reproduction

`frontend/e2e/scenarios.ts` supplies source-shaped demo payloads through all
four simulated source adapters, using fixed 2026-01-01 UTC instants.
`frontend/e2e/operational.spec.ts` executes normal and violated E01–E04 flows,
UI investigation/fallback, resolution and controlled retry, then resets and
repeats. It also registers/edits/distributes a product to ERP, Mercado Livre
and Shopee, preserving a failed Shopee attempt after retry succeeds.
`docs/frontend_validation.md` contains the setup order, executed results and
screenshots. This route to verification does not require Postman.

For the joint JEV + external Agent E02 demonstration, use the operator driver
`node scripts/mcp-e02-demo.mjs prepare --require-jev` with `demo,mcp` profiles.
It creates a resettable fictitious product and adapter events, checks the
interpretation through both REST and MCP, then prints a Codex investigation
prompt. Approval is a separate human decision in that occurrence; only the
PEEK engine can provide VERIFIED. Setup and fallback are in
[mcp_agent.md](mcp_agent.md); the local validation record is in
[jev_agent_validation.md](jev_agent_validation.md).

## 12. Interactive scenario runner

`/simulation` centralizes Normal, E01, E02, E03 and E04 using the existing
source adapters, inventory/fiscal commands and deterministic evaluation.
Normal confirms stock and fiscal commands and a matching physical count.
E01 omits the stock confirmation (expected 95, system 100); E02 confirms
stock 95 and counts 93; E03 omits fiscal confirmation; E04 receives 50 and
registers 47 (baseline 100, expected 150, system 147). The screen verifies
the persisted product context before claiming success and links the generated
exception and product. It neither accepts E02 checkpoints nor resolves alerts.

The session owns one `DEMO-*` namespace in sessionStorage. Executing another
scenario replaces only this namespace through `DemoResetService`; reset is
repeatable and removes partial runs as well. The demo profile must enable
reset; fixture mode remains read-only. Scenario timestamps start from the
backend product creation clock, and evaluation advances beyond actual command
deadlines. Evaluation is global, as already documented by the existing API,
so run on an isolated demo database. The UI disables overlapping actions.

`/inventory` displays current API snapshots. A confirmed physical count may
reanchor current expected stock; an open E02 remains marked as a divergence
and links to the pre-checkpoint detection evidence. Missing evidence is
shown explicitly. `/overview` aggregates current contexts and exceptions
without adding analytics. Fiscal links to the read-only `/fiscal` screen; no
fiscal issuance is implemented by these screens.
