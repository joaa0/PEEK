# Operational Reconciliation MVP

Hackathon MVP for a complementary operational layer for small and medium multichannel retailers.

The system receives events from existing commercial, inventory, fiscal, marketplace, and physical-operation sources, normalizes them, correlates expected versus observed behavior, and creates explainable operational exceptions.

## Core idea

```text
Sources
  ↓
Event ingestion
  ↓
Normalization
  ↓
Operational state
  ↓
Reconciliation rules
  ↓
Exception engine
  ↓
Evidence + impact
  ↓
Recommendation
  ↓
Manager
```

The application is not intended to replace the client's ERP or operational systems. Those systems remain systems of record.

## MVP

The hackathon MVP focuses on four exception types:

- E01 — stock synchronization failure;
- E02 — physical versus system stock divergence;
- E03 — physical exit without fiscal document;
- E04 — divergent goods receipt.

The demo must prove that an operational inconsistency that currently depends on manual conference can be detected automatically, explained, and presented with a recommended action.

## Project documentation

Read:

- `AGENTS.md`
- `docs/project_context.md`
- `docs/product_scope.md`
- `docs/domain_model.md`
- `docs/architecture.md`
- `docs/exception_engine.md`
- `docs/demo_scenarios.md`
- `docs/technical_decisions.md`
- `docs/glossary.md`

## Repository layout

The source projects have not yet been scaffolded. The intended layout for the
selected architecture is:

```text
.
├── AGENTS.md
├── README.md
├── docs/
├── backend/                 # Java 21 / Spring Boot, JPA, Flyway
│   └── src/main/ and src/test/
├── frontend/                # Next.js / TypeScript / Tailwind CSS
├── compose.yaml             # PostgreSQL only at first
├── assets/
└── scripts/
```

## Selected stack

- Frontend: Next.js, TypeScript, Tailwind CSS.
- Backend: Java 21, Spring Boot modular monolith.
- Database: PostgreSQL via Docker Compose for local development.
- Persistence and migrations: Spring Data JPA/Hibernate and Flyway.
- AI: external LLM API behind the backend intelligence module, with a
  deterministic fallback when unavailable.

The stack is selected, but the projects, Compose file, and runnable commands
are not yet present. The scaffold issues must add reproducible equivalents
for:

```text
make dev
make test
make lint
make build
```

or equivalent commands through the chosen ecosystem.
