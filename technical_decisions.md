# Technical Decisions

## 1. Purpose

This document separates:

- decisions already implied by the product;
- recommended implementation constraints;
- decisions still intentionally open.

Do not treat unresolved items as if they had already been selected.

---

## 2. Accepted product-architecture decisions

### TD-001 — Complementary layer, not ERP replacement

**Status:** Accepted

The application sits above/beside existing operational systems.

External systems remain systems of record.

---

### TD-002 — Event-oriented internal model

**Status:** Accepted

External system payloads must be transformed into normalized internal events before domain reconciliation.

---

### TD-003 — Vendor adapters

**Status:** Accepted

SAP, TOTVS, Bling, Olist, marketplaces, fiscal systems, and mock sources should be isolated behind adapters.

Core domain rules must not depend on vendor payloads.

---

### TD-004 — Deterministic rules before AI

**Status:** Accepted

Objective violations must be detected using deterministic rules.

AI/JEV is reserved for interpretation, probable cause, context, and recommendation.

---

### TD-005 — Evidence-backed exceptions

**Status:** Accepted

Every exception must expose evidence and the expected/observed discrepancy.

---

### TD-006 — Customer-specific parameters separated from universal events

**Status:** Accepted

Timeouts, tolerances, correlation fields, source configuration, and severity must be configurable rather than embedded directly in reusable rule code.

---

### TD-007 — Mock integrations are valid for the hackathon

**Status:** Accepted

The MVP may simulate source systems with mock APIs, local databases, fixtures, or webhooks.

Real ERP integration is not required.

Mock adapters also cover outbound Product create/update operations. PEEKio
persists the canonical Product and each per-destination command/attempt/result;
only the external destination effect is simulated. A successful simulated
response may return the destination's external product identifier.

---

### TD-008 — AI is not a hard dependency for deterministic alerts

**Status:** Accepted

AI failure must not suppress or invalidate a rule-based exception.

---

## 3. Data-design decisions

### TD-009 — Stable event identity

**Status:** Accepted

Each normalized event needs a stable identifier to support idempotency and traceability.

---

### TD-010 — Immutable event history

**Status:** Recommended / strong constraint

Source events should be treated as append-only observations.

Corrections should preferably be represented as new events or explicit state changes, not silent mutation of history.

---

### TD-011 — Explicit timestamps

**Status:** Accepted

Operational events need unambiguous timestamps.

At minimum, distinguish when the event occurred from when the platform received it if this distinction matters to reconciliation.

---

### TD-012 — Derived state distinguished from source-reported state

**Status:** Accepted

`expected_stock` or other derived operational state must not be confused with a value reported by an external inventory system.

---

## 4. Processing decisions

### TD-013 — Synchronous processing is acceptable for the MVP

**Status:** Open but allowed

A queue or event broker is not required for the hackathon.

A synchronous implementation is acceptable if the code preserves the logical pipeline:

```text
ingestion → normalization → state → reconciliation → exception
```

Do not add Kafka/RabbitMQ/etc. only for architectural appearance.

---

### TD-014 — Timeout rules need controllable time

**Status:** Accepted

Tests and demo flows involving timeouts should support:

- fixed clocks;
- simulated time;
- small configurable demo timeouts;
- explicit manual evaluation.

Do not require waiting several real minutes during the live demo.

---

### TD-015 — Idempotent ingestion

**Status:** Accepted

The same source event delivered twice must not create duplicate operational effects or duplicate exceptions.

---

## 5. AI/JEV decisions

### TD-016 — Structured AI input

**Status:** Accepted

AI receives structured evidence and exception context rather than raw unbounded source data.

---

### TD-017 — Structured AI output

**Status:** Recommended

Prefer validated structured output containing fields such as:

```text
explanation
probable_causes
impact
recommended_action
confidence
```

---

### TD-018 — AI must distinguish fact from hypothesis

**Status:** Accepted

AI-generated probable causes must not be presented as confirmed facts unless supported directly by evidence.

---

## 6. UI decisions

### TD-019 — Exception list is the main product surface

**Status:** Accepted

The primary dashboard centers on operational exceptions rather than generic metrics.

---

### TD-020 — Investigation view

**Status:** Accepted

Opening an exception should show:

- problem;
- expected state;
- observed state;
- evidence;
- impact/context;
- recommendation.

---

### TD-021 — No full BI suite

**Status:** Accepted

Charts may be used where helpful, but analytics/reporting is not the central MVP.

---

## 7. Security decisions

### TD-022 — No real production credentials/data

**Status:** Accepted

Hackathon data should be fictitious.

Secrets belong in environment variables and must not be committed.

---

### TD-023 — Fiscal and operational data treated as sensitive

**Status:** Accepted

Avoid logging complete sensitive payloads unnecessarily.

The MVP should still preserve enough traceability for evidence.

---

## 8. Selected implementation architecture

The project team selected this stack for the MVP:

| Layer | Decision |
| --- | --- |
| Frontend | Next.js, TypeScript, Tailwind CSS |
| Backend | Java 21, Spring Boot |
| Database | PostgreSQL |
| Persistence | Spring Data JPA with Hibernate |
| Database migrations | Flyway |
| Local infrastructure | Docker Compose, initially only PostgreSQL |
| AI | External LLM API, isolated behind the intelligence layer |
| Application structure | Modular monolith |

**Status:** Accepted. This is an architecture decision, not a claim that the
application or its development commands are already implemented.

The Next.js frontend communicates with the Spring Boot backend through an API.
The backend owns ingestion, adapters, operational state, deterministic rules,
exceptions, persistence, and the LLM client. Modules remain separated by domain
responsibility inside one deployable backend application. JPA/Hibernate maps
persistent entities; Flyway owns schema changes. Do not rely on automatic schema
generation to replace migrations.

Docker Compose starts PostgreSQL for local development. The frontend and backend
run as local development processes until the team explicitly chooses otherwise.
No message broker, production ERP connector, or additional container is required
for the initial MVP. This preserves the synchronous pipeline allowed by TD-013.

The external LLM provider and model have not yet been chosen. The provider must
be configurable, use secrets outside version control, receive structured evidence,
and fail without suppressing deterministic alerts (TD-008, TD-016, TD-018).

### Decisions still open

- Exact dependency versions and build tooling when the projects are scaffolded.
- API, event, and exception schemas, plus PostgreSQL table design.
- External LLM provider/model and operational fallback configuration.
- Authentication and deployment approach; neither is central to the demo.

Avoid letting deployment work consume time needed for the end-to-end exception flow.

---

## 9. Implementation order

With the stack selected, define only the remaining contracts needed to begin:

1. scaffold the Spring Boot and Next.js applications and local PostgreSQL service;
2. define API, event, and exception schemas;
3. create Flyway migrations and JPA mappings;
4. choose the mock source strategy and local development commands;
5. select an LLM provider/model and preserve a deterministic fallback.

Then implement one vertical slice:

```text
SALE_CONFIRMED
    ↓
missing STOCK_UPDATED
    ↓
E01
    ↓
investigation UI
```

Do not model every future integration before E01 works end-to-end.

---

## 10. Definition of a good technical decision

A technical choice is appropriate if it:

- preserves event traceability;
- keeps rules testable;
- separates vendor adapters;
- supports the demo;
- minimizes unnecessary infrastructure;
- can evolve without rewriting the domain;
- does not expand product scope.
