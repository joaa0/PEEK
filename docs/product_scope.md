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
13. optional AI/JEV explanation for ambiguous cases.

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
- full product catalog/PIM;
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
10. the exception being acknowledged or resolved.

---

## 12. Post-MVP expansion directions

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
