# Glossary

## API

Application Programming Interface.

Used by systems to exchange data programmatically.

---

## Adapter

A boundary component that translates a vendor/source-specific payload into the application's normalized event format.

Example:

```text
Marketplace payload
      ↓
Marketplace adapter
      ↓
SALE_CONFIRMED
```

---

## Correlation

The process of determining which events belong to the same operational case.

Common correlation keys:

- SKU;
- order ID;
- invoice ID;
- receipt ID;
- movement ID.

---

## ERP

Enterprise Resource Planning.

In this project, the ERP normally remains a system of record.

The product does not replace it.

---

## Event

A record indicating that something happened or was reported.

Examples:

- sale confirmed;
- stock updated;
- invoice issued;
- physical count;
- goods received.

---

## Event Engine

The set of components responsible for receiving, normalizing, storing/processing, and correlating events.

---

## Exception

An operational inconsistency detected when expected behavior does not match observed behavior.

Examples:

- sale without stock update;
- physical count different from expected stock;
- physical exit without fiscal document.

---

## Exception-First

Product principle in which the user is primarily shown situations requiring attention instead of being forced to inspect every normal operational event.

---

## Evidence

Concrete data that supports an exception.

Examples:

- source event;
- missing expected event;
- physical count;
- expected-state calculation;
- quantity difference;
- timeout.

---

## Expected State

The state the system derives should exist based on known events and process rules.

Example:

```text
initial stock 100
sale -5
expected stock 95
```

---

## Observed State

The state reported by an external system or measured physically.

Example:

```text
inventory system reports 100
physical count reports 93
```

---

## Operational State

Derived representation of the current business/physical situation based on known events.

It is different from any individual external system's current state.

---

## Operational Truth

Project concept representing the best operational interpretation obtained by comparing:

- what should have happened;
- what systems recorded;
- what physically happened.

The MVP uses available evidence and does not claim perfect reconstruction of reality.

---

## JEV

Name used in project discussions for the intelligent/AI evaluation layer used when a case requires contextual interpretation of multiple signals.

JEV should not replace deterministic rules for objective conditions.

Typical responsibilities:

- probable cause;
- explanation;
- context;
- recommendation;
- confidence.

---

## Deterministic Rule

A rule whose result is defined explicitly by inputs and conditions.

Example:

```text
IF sale exists
AND stock update is missing after 5 minutes
THEN create E01
```

---

## Reconciliation

Comparison of expected and observed operational behavior to identify discrepancies.

---

## System of Record

The authoritative system for a given transaction or business object.

Examples:

- ERP;
- fiscal system;
- inventory platform.

This product complements systems of record rather than replacing them.

---

## Process Template

Reusable representation of an expected operational sequence.

Example:

```text
Sale
  ↓
Inventory update
  ↓
Invoice
  ↓
Physical exit
```

---

## Timeout

Maximum configured time allowed for an expected event to occur before a missing-event exception is created.

---

## Tolerance

Allowed numeric difference before a divergence becomes an exception.

Example:

```text
expected = 100
observed = 99
tolerance = 1
```

No exception is required if the difference is within the accepted tolerance.

---

## SKU

Stock Keeping Unit.

Identifier for a specific sellable product or variation.

---

## Product

The minimum canonical operational product record created and maintained in
PEEKio. It supports correlation and simulated outbound registration; it is not
a complete catalog/PIM record. See `docs/domain_model.md` for its model.

---

## ProductChannelMapping

The link between a canonical Product and one destination system, including the
external product identifier returned after successful propagation.

---

## ProductPropagationAttempt

An immutable record of one create/update attempt for one destination, including
its status and success result or failure evidence. Retries create additional
attempts.

---

## WMS

Warehouse Management System.

Warehouse-oriented inventory and logistics software.

Not part of the MVP.

---

## MES

Manufacturing Execution System.

Software focused on shop-floor production execution.

Not part of the MVP.

---

## MRP

Material Requirements Planning.

Planning method/software for production and materials.

Not part of the MVP.

---

## ATP

Available-to-Promise.

Availability based primarily on what can be committed from known stock/supply.

Relevant to broader research, but not part of the current MVP.

---

## CTP

Capable-to-Promise.

Availability based on what the organization can produce or fulfill within a period.

Relevant to broader research, but not part of the current MVP.

---

## Idempotency

Property that allows the same event/request to be processed repeatedly without producing duplicated business effects.

Critical when external systems may retry webhook or API delivery.

---

## Source Event

The original event/payload produced by an external system before normalization.

---

## Normalized Event

Internal vendor-independent event used by domain logic.

---

## E01

`STOCK_SYNC_FAILURE`

A sale was confirmed but the expected stock update did not occur.

---

## E02

`PHYSICAL_STOCK_DIVERGENCE`

Physical stock differs from expected/system stock beyond tolerance.

---

## E03

`PHYSICAL_EXIT_WITHOUT_FISCAL`

A physical exit exists without a correlated fiscal document.

---

## E04

`RECEIPT_DIVERGENCE`

The physical receipt quantity differs from the registered inventory quantity.
