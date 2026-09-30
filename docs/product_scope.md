# Product Scope

## 1. MVP objective

The MVP exists to prove one capability clearly:

> Data originating from different operational systems can be correlated to automatically identify operational inconsistencies and present evidence and an actionable recommendation.

The purpose of the hackathon implementation is not to rebuild the entire operational ecosystem of a retailer.

---

## 2. MVP capabilities

The MVP includes:

1. simulated operational sources;
2. event ingestion;
3. event normalization;
4. event persistence or equivalent traceability;
5. operational-state reconstruction;
6. deterministic reconciliation;
7. four canonical exception types;
8. exception list/dashboard;
9. exception investigation;
10. evidence visualization;
11. impact/context presentation;
12. recommendation;
13. optional AI/JEV explanation for ambiguous cases;
14. canonical Product Master and channel/source mappings for operational identity;
15. command-based inventory synchronization and fiscal orchestration through adapters;
16. operational views for product identity, stock synchronization, and fiscal status;
17. centralized minimal Product registration in PEEKio with simulated
    per-destination propagation.

### 2.1 Operational master data and orchestration

Product Master holds the minimum canonical product identity needed to correlate
events and orchestrator commands. Channel/source mappings connect external item
identifiers to that identity. This is an operational registry, not a content
catalog or PIM.

Orchestrators accept explicit commands, dispatch them through adapters, and
record attempts and external confirmations as auditable operational state.
Sending a command is not proof of success; only a correlated external
confirmation establishes completion. Retry/reprocessing must be explicit,
idempotent, and traceable.

Inventory synchronization may be initiated by PEEKio, while the connected
inventory/channel system remains authoritative for its resulting stock state.
Fiscal orchestration may request work from an external fiscal system, while
document issuance and legal validation remain external responsibilities.

The product-registration flow also lets an operator create/update a minimal
canonical Product once in PEEKio and select one or more destination systems.
PEEKio creates an independent propagation command and traceable attempt for
each destination. Hackathon destination adapters simulate external create/update
operations; the Product, mappings, attempts, results, and retry history are
persisted by the application. A successful response may supply the external
identifier stored on the corresponding ProductChannelMapping. Failures remain
visible per destination with status and evidence for investigation and retry.

The minimum Product data may include name, internal SKU, basic description,
price, optional EAN/GTIN, basic category, and fiscal fields required by a
defined flow. This does not add SEO, advanced media, complete channel-specific
categories, complex price tables, advanced logistics rules, or full content
enrichment.

---

## 3. Simulated data sources

The hackathon may represent external systems through:

- REST mock APIs;
- separate local databases;
- simulated webhooks;
- fixture files;
- local event generators.

### 3.1 Commercial source

May produce:

- order identifier;
- sale event;
- SKU;
- quantity;
- timestamp.

### 3.2 Inventory source

May produce:

- SKU;
- stock quantity;
- inventory movement;
- timestamp.

### 3.3 Fiscal source

May produce:

- invoice/document identifier;
- order identifier;
- SKU;
- quantity;
- timestamp.

### 3.4 Physical-operation source

Optional source producing:

- physical count;
- receipt;
- physical exit;
- adjustment;
- timestamp.

---

## 4. Canonical MVP exceptions

### E01 — STOCK_SYNC_FAILURE

Expected relationship:

```text
SALE_CONFIRMED
      ↓
STOCK_UPDATED
```

Failure condition:

A confirmed sale exists, but the expected stock update does not occur within the configured interval.

Example:

```text
Initial stock: 100
Sale: -5
Expected stock: 95
Observed stock: 100
```

Result:

```text
STOCK_SYNC_FAILURE
```

---

### E02 — PHYSICAL_STOCK_DIVERGENCE

Expected relationship:

```text
PHYSICAL_COUNT ~= EXPECTED_STOCK
```

Failure condition:

A physical count differs from expected/system stock beyond configured tolerance.

Example:

```text
Expected stock: 95
Physical count: 93
Difference: -2
```

Result:

```text
PHYSICAL_STOCK_DIVERGENCE
```

Possible investigation hypotheses may include:

- loss;
- breakage;
- unregistered removal;
- unregistered movement;
- counting error.

The deterministic system should detect the divergence. AI/JEV may help interpret the cause.

---

### E03 — PHYSICAL_EXIT_WITHOUT_FISCAL

Expected relationship:

```text
PHYSICAL_EXIT
      ↓
INVOICE_ISSUED
```

Failure condition:

A physical exit exists but no correlated fiscal document can be found within the expected process window.

Result:

```text
PHYSICAL_EXIT_WITHOUT_FISCAL
```

---

### E04 — RECEIPT_DIVERGENCE

Expected relationship:

```text
GOODS_RECEIVED
      ↓
STOCK_UPDATED
```

Failure condition:

The quantity physically received differs from the quantity registered in inventory.

Result:

```text
RECEIPT_DIVERGENCE
```

---

## 5. Exception investigation flow

Opening an exception should provide:

```text
Problem
   ↓
Evidence
   ↓
Expected state
   ↓
Observed state
   ↓
Impact
   ↓
Probable cause, when available
   ↓
Recommended action
```

The UI should not be a passive dashboard. It should behave as an operational investigation trail.

---

## 6. AI/JEV scope

AI may explain and contextualize an exception.

AI should **not** replace deterministic detection when the violation is objective.

Suggested inputs:

- exception code;
- triggered rule;
- relevant normalized events;
- recent event history;
- expected state;
- observed state;
- thresholds/tolerances;
- source systems.

Suggested outputs:

- concise explanation;
- probable cause;
- relevant evidence summary;
- operational impact;
- investigation suggestion;
- recommended action;
- confidence when useful.

The UI must preserve access to the underlying evidence.

---

## 7. Dashboard scope

The dashboard should be intentionally small.

It may include:

- critical exceptions;
- attention/warning exceptions;
- resolved exceptions;
- filters by exception type;
- filters by source or SKU when useful;
- status;
- timestamp.

The main interaction is:

```text
Alert
  ↓
Open investigation
  ↓
Understand evidence
  ↓
Take or confirm action
```

Do not build a generic analytics suite.

---

## 8. Explicit non-goals

The following must not be implemented as part of the MVP unless scope is explicitly changed:

- complete ERP;
- WMS;
- complete fiscal issuance;
- generic marketplace hub;
- complete product catalog/PIM (the scoped Product Master is a minimal
  operational record with simulated per-destination propagation; it excludes
  content enrichment and advanced channel publishing);
- real marketplace/catalog integrations;
- general-purpose BI;
- forecasting;
- demand planning;
- MRP;
- MES;
- APS;
- RFID platform;
- generic IoT platform;
- dozens of real integrations;
- general automation builder similar to Zapier;
- autonomous AI executing critical operational decisions.

---

## 9. Scope-control question

Before adding a feature, ask:

> Does this feature directly help prove operational reconciliation?

If the answer is no, it probably does not belong in the hackathon MVP.

---

## 10. What may be simulated

For the MVP, it is acceptable to simulate:

- ERP;
- inventory system;
- fiscal system;
- marketplace;
- physical-operation scanner;
- webhooks;
- delays;
- missing events;
- conflicting state;
- fiscal documents;
- customer-specific configuration.

The system should still preserve realistic architectural boundaries even when sources are simulated.

---

## 11. What the MVP should demonstrate live

At minimum, the demo should show:

1. normal source events entering the platform;
2. normalized events being accepted;
3. expected operational state being derived;
4. a deliberate violation being introduced;
5. a rule detecting the violation;
6. an exception appearing;
7. evidence being available;
8. expected versus observed state being visible;
9. recommendation being displayed;
10. the exception being acknowledged or resolved;
11. an operator initiating a stock/fiscal operation, seeing its confirmation
    or failure, and investigating an exception when confirmation is absent;
12. retry/reprocessing with visible attempt history.
13. one canonical Product propagated to multiple simulated destinations, with
    independent success/failure, external identifiers, and a traceable retry.

---

## 12. Post-MVP expansion directions

The local external-agent MVP supports bounded E01 inventory retry and E02
acceptance of an existing confirmed physical checkpoint. E01 safe retry may
be automatic when eligible. E02 requires presentation of facts/evidence and
separate JEV hypotheses, followed by explicit human approval in Codex App/CLI.
Human approval authorizes an attempt, not resolution. E03/E04 remain read-only;
PEEK retains final deterministic verification. This does not authorize autonomous
E02 correction or arbitrary critical business actions. See [mcp_agent.md](mcp_agent.md).

These are future directions, not current requirements:

- additional operational exception types;
- more source adapters;
- production/quality/logistics events;
- process templates by company;
- configurable rule packs;
- customer-level thresholds;
- automated safe remediation;
- richer exception prioritization;
- better physical event capture;
- product/engineering correlation;
- capacity/ATP/CTP integration.

They should not influence the MVP unless explicitly selected.
