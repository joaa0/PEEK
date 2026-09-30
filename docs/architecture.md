# Architecture

## 1. Architectural objective

The architecture should prove a realistic product pipeline without requiring real ERP, marketplace, or fiscal integrations during the hackathon.

The selected implementation is a modular monolith: a Next.js/TypeScript/Tailwind
frontend, one Java 21/Spring Boot backend, and PostgreSQL. Spring Data JPA with
Hibernate provides persistence, Flyway applies schema migrations, and Docker
Compose initially runs only PostgreSQL locally. An external LLM API is called
through an isolated intelligence module. See `technical_decisions.md` for
accepted decisions and remaining choices.

The core concept is:

```text
Sources
  ↓
Event Ingestion
  ↓
Normalization
  ↓
Operational State
  ↓
Reconciliation
  ↓
Exception Engine
  ↓
Evidence / Impact
  ↓
AI/JEV Contextualization
  ↓
Presentation
```

Operational execution adds a command path alongside event ingestion:

```text
Operator / deterministic rule
  ↓ command with product and channel mapping
Product, Inventory, or Fiscal Orchestrator
  ↓ adapter dispatch + attempt history
External or simulated system
  ↓ confirmation event / document reference
Operational state → reconciliation (E01 / E03) → evidence and UI
```

The Product Master and Channel Mapping module resolves canonical product
identity for commands, events, and operational views. It stores only the
minimum identity and external identifiers needed for correlation.

Product registration has a separate outbound command path: the operator saves
the canonical Product, selects targets, and PEEKio dispatches one
create/update command per target through a simulated destination adapter.
Each target's attempt and result are persisted independently. Success may
return an `external_id` that updates its ProductChannelMapping; failure keeps
status and evidence for investigation and retry. The Product and propagation
history are real application data even though destination effects are mocked.

---

## 2. System boundary

### Inside the application

- adapters;
- event ingestion;
- normalized-event model;
- operational-state calculation;
- event correlation;
- deterministic rules;
- exception creation;
- evidence aggregation;
- recommendation generation;
- optional AI/JEV layer;
- exception UI/API;
- minimal Product Master and channel/source mapping;
- inventory and fiscal orchestration command/attempt tracking;
- canonical Product registration and per-destination propagation commands,
  attempts, mappings, and results.

### Outside the application

- ERP;
- marketplace;
- e-commerce;
- inventory system;
- fiscal system;
- WMS;
- physical devices;
- real accounting/fiscal issuance;
- production systems.

For the MVP, external systems may be simulated. Orchestrators may dispatch to
simulated adapters, but PEEKio does not become the inventory or fiscal system
of record and does not implement legal fiscal issuance.

---

## 3. High-level architecture

Implementation boundary:

```text
Next.js frontend (TypeScript + Tailwind)
                 │ HTTP API
                 ▼
Spring Boot modular monolith (Java 21)
  adapters → ingestion → events → operational state → reconciliation
                                      │
                         exceptions + evidence
                                      │
                         intelligence → external LLM API
                 │
       Spring Data JPA / Hibernate
                 │
             PostgreSQL
          (Flyway migrations)
```

Docker Compose provides PostgreSQL for local development; frontend and backend
run as local processes. The backend modules shown here are logical boundaries,
not separate services or deployables. The LLM path enriches existing exceptions
and must not decide whether a deterministic rule has fired.

Domain processing pipeline:

```text
                 EXTERNAL / MOCK SOURCES

   Sales        Inventory        Fiscal        Physical
     │              │              │              │
     └───────┬──────┴──────┬───────┴──────┬──────┘
             │             │              │
             ▼             ▼              ▼

                      ADAPTER LAYER
                           │
                           ▼
                    EVENT INGESTION
                           │
                           ▼
                      NORMALIZATION
                           │
                           ▼
                    NORMALIZED EVENTS
                           │
                           ▼
                  OPERATIONAL STATE
                           │
                           ▼
                RECONCILIATION ENGINE
                    ┌──────┴──────┐
                    │             │
                    ▼             ▼
            deterministic       JEV/AI
                rules          interpretation
                    │             │
                    └──────┬──────┘
                           ▼
                   EXCEPTION ENGINE
                           │
                           ▼
              EVIDENCE + IMPACT + ACTION
                           │
                           ▼
                    API / DASHBOARD
```

---

## 4. Adapter layer

Each external source should be translated through an adapter.

Responsibilities:

- receive vendor/source payload;
- validate minimal structure;
- preserve source identifiers;
- map source fields;
- produce canonical event type;
- attach source metadata;
- reject unsupported/malformed payloads explicitly.

Adapters must not contain reconciliation rules.

Outbound destination adapters translate a canonical Product create/update
command into a destination-specific request and normalize its success/failure
into an application result. In the hackathon these adapters are simulated.
They must not place channel-specific publication rules in the Product domain.

Example:

```text
Mock Sales Payload
       ↓
SalesAdapter
       ↓
SALE_CONFIRMED
```

---

## 5. Event ingestion

Responsibilities:

- accept normalized or adapter-translated events;
- reject malformed events;
- check idempotency;
- persist or register the event;
- emit/trigger domain processing.

The ingestion layer should not decide whether the event represents a problem.

---

## 6. Normalization

Normalization creates a vendor-neutral internal language.

Example:

```text
SAP-specific sale event
Bling-specific order event
Mock marketplace sale event
             ↓
      SALE_CONFIRMED
```

This is essential because the product thesis depends on reusable correlation logic.

---

## 7. Event storage

A persisted event log is recommended even for the MVP if implementation time allows.

Benefits:

- auditability;
- replay;
- evidence;
- deterministic testing;
- easier debugging;
- reconstruction of derived state.

The exact storage technology is intentionally not prescribed yet.

If a persistent event store is not implemented, preserve equivalent traceability in the MVP.

---

## 8. Operational-state layer

Responsibilities:

- derive expected quantities/state;
- read relevant historical events;
- maintain or calculate current derived state;
- distinguish derived state from source snapshots.

Initial stock example:

```text
expected_stock =
    initial_stock
  + receipts
  - sales
  - registered_losses
  + adjustments
```

The MVP does not require full inventory accounting.

---

## 9. Correlation layer

Correlation decides which events belong to the same operational case.

Possible correlation fields:

- SKU;
- order ID;
- invoice ID;
- receipt ID;
- movement ID;
- source reference;
- customer-specific key.

Rules should define the required correlation strategy.

Avoid "correlate everything by timestamp" as the default approach.

---

## 10. Reconciliation engine

The reconciliation engine evaluates expected relationships.

Responsibilities:

- execute deterministic rules;
- compare expected and observed state;
- identify missing expected events;
- apply timeouts;
- apply tolerances;
- produce exception candidates.

Example:

```text
SALE_CONFIRMED
      ↓
wait/configured window
      ↓
correlated STOCK_UPDATED exists?
   ├── yes → no exception
   └── no  → E01
```

---

## 11. Exception engine

The exception engine converts a detected violation into a user-facing operational exception.

Responsibilities:

- create exception identity;
- assign type/code;
- set severity;
- collect evidence;
- capture expected state;
- capture observed state;
- build user-facing context;
- attach recommendation;
- support lifecycle/status.

It should be possible to inspect why an exception exists.

---

## 12. Intelligence layer

JEV/AI is optional for deterministic detection and useful for ambiguous interpretation.

Input should be structured.

Example:

```text
{
  exception_code,
  rule_triggered,
  expected_state,
  observed_state,
  evidence,
  recent_related_events,
  configuration
}
```

AI may return:

```text
{
  explanation,
  probable_cause,
  impact,
  recommended_action,
  confidence
}
```

Do not let AI invent source evidence.

The application should clearly distinguish:

- factual event evidence;
- calculated state;
- AI-generated interpretation.

---

## 13. Presentation layer

The primary UI is a **central exception view**.

Suggested views:

### Dashboard / exception list

- exception title;
- code/type;
- severity;
- affected SKU/order;
- detected time;
- status.

### Investigation view

- summary;
- expected state;
- observed state;
- evidence timeline;
- relevant events;
- possible cause;
- impact;
- recommendation;
- resolve/acknowledge action.

The UI should optimize for investigation rather than analytics.

---

## 14. Suggested logical modules

The selected stack uses separate frontend and backend projects. The following
names describe backend domain packages under the Java source tree, rather than
top-level repositories or microservices. The frontend owns presentation code.

```text
backend/src/main/java/.../
├── ingestion/
├── integrations/
├── events/
├── operational_state/
├── reconciliation/
├── exceptions/
├── intelligence/
└── presentation/
```

Possible responsibilities:

### `ingestion/`

- input contracts;
- idempotency;
- event intake.

### `integrations/`

- source adapters;
- mock sources.

### `events/`

- normalized event model;
- event vocabulary;
- event repository.

### `operational_state/`

- state calculators;
- expected stock/state.

### `reconciliation/`

- correlation;
- rules;
- timeouts;
- tolerance evaluation.

### `exceptions/`

- exception entities;
- evidence;
- lifecycle;
- recommendations.

### `intelligence/`

- JEV/AI client;
- structured prompts;
- output validation;
- fallback behavior.

### `presentation/`

- HTTP API;
- HTTP controllers and response mapping.

The dashboard and investigation UI belong in the Next.js frontend. Tests should
follow the relevant project conventions, including backend tests under
`backend/src/test/` and frontend tests alongside or under the frontend source
tree once its tooling is selected. Flyway migrations belong in the backend
resources tree.

---

## 15. Synchronous versus asynchronous processing

The initial modular monolith may process events synchronously while preserving
the conceptual event pipeline and idempotency. A queue is not part of the
selected local MVP architecture.

A more production-oriented architecture could later introduce:

- message queue;
- event broker;
- worker;
- retries;
- dead-letter processing.

Do not introduce distributed infrastructure merely to make the architecture appear sophisticated.

---

## 16. Idempotency

Sources may deliver events more than once.

The ingestion path should use a stable event identifier or equivalent deduplication strategy.

Conceptual rule:

```text
same source + same external event ID
    ↓
same normalized event
    ↓
no duplicate operational effect
```

---

## 17. Auditability

For each exception, it should be possible to answer:

- Which rule triggered?
- Which events were considered?
- Which event was missing?
- What was expected?
- What was observed?
- Which threshold/tolerance was used?
- When was the exception created?
- What recommendation was presented?

---

## 18. Failure isolation

A malformed source event should not invalidate the full pipeline.

A failed AI request should not suppress a deterministic exception.

AI failure fallback:

```text
rule detected problem
      ↓
AI unavailable
      ↓
show deterministic exception + evidence + static recommendation
```

---

## 19. Architecture constraints

1. Adapters must not contain business reconciliation logic.
2. Reconciliation logic must not depend on UI.
3. Reconciliation logic must not depend on AI availability.
4. AI must not become the source of record.
5. Vendor-specific payloads must not leak into core rules.
6. Evidence must be preserved.
7. Timeouts and tolerances should be configurable.
8. The MVP may use mocks but should preserve realistic boundaries.

## 20. Local external-agent boundary

The explicitly enabled local MCP profile exposes a closed set of semantic
tools to an external Codex client. It reuses domain query services and
CommandService.retry; it does not embed an LLM provider. Only E01 inventory
retry is writable. E02/E03/E04 remain read-only.

AgentActionExecution audits the request separately from the existing Attempt.
Confirmation enters through the existing adapters/EventService, and the
EvaluationService independently verifies recovery. A local scheduler invokes
that engine; no MCP tool can invoke resolution or inject confirmation.
REST and MCP share OperationalContextService. See [mcp_agent.md](mcp_agent.md).
