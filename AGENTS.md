# AGENTS.md

## 1. Purpose

This repository contains the MVP for a hackathon project focused on **operational reconciliation for small and medium multichannel retailers**.

The product is **not** intended to replace an ERP, WMS, marketplace hub, fiscal system, MES, APS, PIM, or business intelligence platform.

It acts as a complementary operational layer that:

1. receives events from existing systems;
2. normalizes those events into a common internal language;
3. reconstructs expected operational state;
4. correlates events and state;
5. detects inconsistencies;
6. creates explainable and actionable exceptions;
7. optionally uses AI/JEV to interpret ambiguous cases.
8. keeps a minimal canonical Product in PEEKio and initiates traceable,
   simulated propagation to user-selected destination systems.

Before implementing domain behavior, read the relevant files under `docs/`.

The local external Codex agent contract is in `docs/mcp_agent.md`. Reuse
CommandService.retry and the existing adapters/attempts. Only E01 inventory
retry is writable through MCP; never expose exception resolution or fabricated
confirmation. PEEK's deterministic reconciliation supplies final verification.

---

## 2. Required Context

The following documents define the project and should be treated as the product source of truth:

- `docs/project_context.md`  
  Problem, target user, product thesis, value proposition, and strategic boundaries.

- `docs/product_scope.md`  
  MVP boundaries, supported scenarios, explicit non-goals, and hackathon constraints.

- `docs/domain_model.md`  
  Core domain concepts, events, state, exceptions, evidence, and configuration.

- `docs/architecture.md`  
  System boundaries, processing pipeline, modules, data flow, and integration strategy.

- `docs/exception_engine.md`  
  Reconciliation rules, event correlation, operational state, JEV/AI responsibilities, and alert behavior.

- `docs/demo_scenarios.md`  
  Canonical scenarios the MVP must demonstrate.

- `docs/technical_decisions.md`  
  Accepted architectural decisions, implementation constraints, and unresolved technical choices.

- `docs/glossary.md`  
  Domain vocabulary and abbreviations.

If code and documentation disagree, do not silently choose one interpretation. Identify the discrepancy and update either the implementation or the documentation deliberately.

---

## 3. Product Invariants

### 3.1 Existing systems remain systems of record

ERP, sales, inventory, fiscal, marketplace, and other external platforms remain authoritative for the transactions they own.

This application observes, correlates, and evaluates operational events.

Do not redesign this system as the master ERP.

### 3.2 Event-oriented core

External information should enter the domain as normalized events.

Vendor-specific payloads must be translated by adapters before reaching domain logic.

Initial canonical event types:

- `SALE_CONFIRMED`
- `STOCK_UPDATED`
- `INVOICE_ISSUED`
- `GOODS_RECEIVED`
- `PHYSICAL_COUNT`
- `STOCK_ADJUSTED`
- `PHYSICAL_EXIT`

Domain logic must not depend directly on SAP, TOTVS, Bling, Olist, marketplace, or other vendor-specific payload shapes.

Outbound product create/update commands must also pass through destination
adapters. Persist the command and each per-destination attempt/result inside
PEEKio; only the external destination effect is simulated for the hackathon.

### 3.3 Expected versus observed state

The system must distinguish:

- what should have happened;
- what the connected systems reported;
- what physically happened, when physical evidence exists.

Operational reconciliation detects meaningful disagreement between those views.

### 3.4 Exception-first UX

The user should not need to continuously inspect multiple systems looking for problems.

Normal operation may remain silent.

When an inconsistency is detected, generate an exception containing the context required to investigate and act.

### 3.5 Evidence before recommendation

Every operational alert must be traceable to concrete evidence.

An exception should expose, when applicable:

- detected problem;
- exception code;
- severity;
- expected state;
- observed state;
- relevant source events;
- timestamps;
- correlation keys;
- affected SKU/order/document;
- impact;
- probable cause;
- recommended action.

Do not present opaque AI conclusions without supporting evidence.

---

## 4. MVP Thesis

The MVP must prove:

> Data originating from different operational systems can be correlated to automatically identify inconsistencies and present the manager with evidence and an actionable recommendation.

The core MVP consists of:

1. event ingestion;
2. event normalization;
3. operational-state calculation;
4. deterministic reconciliation rules;
5. exception generation;
6. exception investigation;
7. evidence presentation;
8. recommendation presentation;
9. AI/JEV explanation for ambiguous cases;
10. a simple exception dashboard;
11. minimal canonical Product create/edit and ProductChannelMapping;
12. simulated create/update propagation to selected destinations, with
    independent attempt status, returned external ID, failure evidence, and
    retry history.

---

## 5. Canonical MVP Exceptions

### E01 — Stock synchronization failure

Expected relationship:

`SALE_CONFIRMED -> STOCK_UPDATED`

Detect when a sale occurred but the stock update did not occur within the configured interval.

### E02 — Physical versus system divergence

Expected relationship:

`PHYSICAL_COUNT ~= EXPECTED_STOCK`

Detect when the physical count differs from expected/system stock beyond a configured tolerance.

### E03 — Physical exit without fiscal document

Expected relationship:

`PHYSICAL_EXIT -> INVOICE_ISSUED`

Detect when a physical exit exists without the expected correlated fiscal document.

### E04 — Divergent goods receipt

Expected relationship:

`GOODS_RECEIVED -> STOCK_UPDATED`

Detect when the quantity physically received differs from the quantity registered in stock.

Do not expand the MVP with additional exception families unless explicitly requested.

---

## 6. Explicit Non-Goals

The following are outside the current hackathon MVP unless scope is explicitly changed:

- complete ERP;
- WMS;
- complete fiscal issuance;
- generic marketplace hub;
- complete product catalog/PIM, SEO, advanced media/enrichment, advanced
  channel publishing, complex price tables, or advanced logistics rules;
- general-purpose BI;
- forecasting;
- demand planning;
- MRP;
- MES;
- APS;
- RFID platform;
- dozens of real production integrations;
- general automation builder similar to Zapier;
- autonomous AI executing critical business actions.

Avoid feature creep.

If a proposed feature begins turning the application into one of those products, reassess the requirement against `docs/product_scope.md`.

---

## 7. AI / JEV Rules

AI is not responsible for detecting objective violations that can be expressed deterministically.

Use deterministic rules when:

- the relationship is explicit;
- values can be compared directly;
- timing thresholds are known;
- the result must be fully auditable.

Example:

```text
IF SALE_CONFIRMED
AND STOCK_UPDATED does not occur within configured timeout
THEN STOCK_SYNC_FAILURE
```

Use AI/JEV only when multiple signals must be interpreted to suggest:

- probable cause;
- contextual explanation;
- operational impact;
- investigation path;
- recommended action.

AI output must never replace the underlying evidence.

Prefer structured output when AI results are consumed by application logic.

---

## 8. Configuration Model

Universal event types and reusable domain rules should remain independent from customer-specific integrations.

Separate:

1. normalized events;
2. process templates;
3. customer configuration.

Customer-specific configuration may include:

- expected timeout;
- tolerance;
- source systems;
- severity;
- correlation fields;
- active process template.

Do not hard-code customer-specific process differences into reusable domain logic.

---

## 9. Integration Design

For the hackathon, integrations may be:

- mocked APIs;
- separate mock databases;
- simulated webhooks;
- fixture files;
- local event generators.

A real SAP, TOTVS, Bling, Olist, marketplace, or fiscal integration is not required to demonstrate the architecture.

Integrations should be implemented through adapters.

Conceptual boundary:

```text
External System
    ↓
Adapter
    ↓
Event Ingestion
    ↓
Normalized Event
```

---

## 10. Repository Structure

The selected stack uses separate source projects. Production code belongs under:

```text
backend/src/main/java/
frontend/src/ (or the Next.js app directory selected during scaffolding)
```

Tests belong under:

```text
backend/src/test/
frontend tests alongside or under frontend source, per selected test tooling
```

Static resources belong under:

```text
assets/
```

Developer utilities belong under:

```text
scripts/
```

Project documentation belongs under:

```text
docs/
```

Prefer feature/domain-oriented modules rather than a large generic utility package.

A reasonable conceptual split is:

```text
backend/src/main/java/.../
├── ingestion/
├── events/
├── operational_state/
├── reconciliation/
├── exceptions/
├── intelligence/
├── integrations/
└── presentation/
```

Treat this as a logical boundary within the Java modular monolith. The Next.js
application owns UI code. Do not create unnecessary directories before they are
needed.

---

## 11. Domain Isolation

Business rules must not live inside:

- HTTP handlers;
- UI components;
- database models;
- vendor adapters;
- AI prompts.

Keep reconciliation rules and operational-state calculations independently testable.

Infrastructure may depend on domain code.

Domain code should avoid depending on framework-specific infrastructure.

---

## 12. Data Integrity

Operational events must be auditable.

Every normalized event should have, at minimum:

- stable event identifier;
- event type;
- source;
- occurred timestamp;
- received timestamp when relevant;
- business correlation identifiers;
- payload or metadata.

Event ingestion should tolerate duplicate delivery.

Prefer idempotent processing.

Do not silently mutate historical source events.

Derived state may be recalculated from events when practical.

Product propagation commands and destination attempts must be auditable per
target. A retry appends an attempt and preserves prior results; a successful
response records the returned external identifier on its
ProductChannelMapping.

---

## 13. Time Handling

Operational reconciliation depends heavily on timing.

Use explicit timestamps.

Avoid comparing local-time strings.

Persist timestamps in an unambiguous format and convert only for presentation.

Timeout-based rules must use configurable thresholds.

Tests involving time must use controlled clocks or fixed timestamps.

---

## 14. Error Handling

Differentiate:

- malformed input;
- unsupported external payload;
- duplicated event;
- domain inconsistency;
- integration failure;
- internal processing error.

An operational inconsistency is not necessarily a software exception.

For example, `SALE_CONFIRMED` without `STOCK_UPDATED` should normally become an operational alert, not an application crash.

---

## 15. Testing Strategy

Every behavior change requires tests.

Prioritize tests for:

- event normalization;
- duplicate-event handling;
- idempotency;
- event correlation;
- expected-state calculation;
- timeout rules;
- tolerance rules;
- exception generation;
- severity;
- evidence collection;
- exception lifecycle;
- AI/JEV fallback behavior;
- Product create/update propagation with independent destination outcomes,
  partial success, returned external IDs, and retry history.

Use deterministic fixtures.

External services must be mocked or clearly marked as integration tests.

Canonical tests should correspond to `docs/demo_scenarios.md`.

---

## 16. Build and Development Commands

No toolchain should be assumed globally installed.

The selected stack is Java 21/Spring Boot, Next.js/TypeScript/Tailwind CSS,
PostgreSQL, Spring Data JPA/Hibernate, Flyway, and Docker Compose initially for
PostgreSQL only. The backend is a modular monolith and accesses an external LLM
API through an isolated intelligence module.

Runnable projects are available in backend/ and frontend/. From backend/, use
Java 21 and Maven 3.9+: mvn test, mvn package, mvn spring-boot:run and mvn verify
(the latter requires the isolated PostgreSQL test database). From frontend/,
use Node 22.18+: npm ci, npm run dev, npm run format:check, npm run lint,
npm test, npm run test:fixtures, npm run build and npm run test:e2e.
The frontend uses the Spring Boot API by default. npm run dev:fixtures starts
an explicit read-only fixture API for UI development without PostgreSQL.
Run builds and browser tests sequentially. See frontend/README.md and
docs/frontend_validation.md for environment setup and the required execution order.

---

## 17. Coding Style

Use the formatter and linter standard for the selected language/framework and commit their configuration.

Default to:

- UTF-8;
- LF line endings;
- spaces instead of tabs;
- descriptive identifiers;
- small cohesive modules;
- explicit domain terminology.

Avoid generic names such as `utils`, `helpers`, `manager`, or `processor` when a domain-specific name exists.

---

## 18. Security

Never commit:

- credentials;
- tokens;
- private keys;
- production connection strings;
- real customer data.

Provide `.env.example` with sanitized variables.

Mock/demo datasets must contain fictitious information.

Treat operational and fiscal information as potentially sensitive.

---

## 19. Change Discipline

Before implementing a substantial feature:

1. identify which documented product behavior it supports;
2. verify that it is inside MVP scope;
3. identify affected domain concepts;
4. implement the smallest coherent change;
5. add or update tests;
6. update documentation if behavior or architecture changed.

Do not introduce speculative abstractions for imagined future requirements.

---

## 20. Documentation Rule

When an implementation introduces a new domain rule, event type, exception type, integration contract, process template, or major architectural decision, update the corresponding file under `docs/`.

Important product behavior must not exist only in code or chat history.

---

## 21. Commit & Pull Request Guidelines

Use concise, imperative commit subjects.

Conventional Commits are acceptable:

- `feat: detect stock synchronization failure`
- `fix: prevent duplicate event processing`
- `test: add physical stock divergence scenarios`
- `docs: document event correlation strategy`

Pull requests should explain:

- problem;
- solution;
- affected domain behavior;
- verification performed;
- configuration or migration impact;
- screenshots for visible UI changes.

Keep changes focused.

