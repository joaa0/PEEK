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

The Backend Core project is available under `backend/`. The frontend has not
been scaffolded yet. The selected layout is:

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

## Backend Core development

Prerequisites: JDK 21 or newer (the Maven compiler emits Java 21 bytecode),
Maven 3.9+, and PostgreSQL 16+. Docker Compose can provide PostgreSQL:

```text
docker compose up -d postgres
cd backend
mvn test
mvn package
mvn spring-boot:run
```

The backend reads `PEEK_DB_URL`, `PEEK_DB_USER`, and `PEEK_DB_PASSWORD`; their
defaults match `compose.yaml` and `.env.example`. Copy `.env.example` to `.env`
only if changing Compose settings. `PEEK_DB_PORT` changes the host port; update
`PEEK_DB_URL` accordingly. The local example password is not a production secret.

Health is available at `GET http://localhost:8080/actuator/health`.
`mvn test` runs domain tests. `mvn verify` also runs the PostgreSQL API/persistence
suite against a dedicated `peek_test` database, configured with
`PEEK_TEST_DB_URL`, `PEEK_TEST_DB_USER`, and `PEEK_TEST_DB_PASSWORD`. The test
database must already exist; the suite applies and validates Flyway migrations.
Never point these test variables at a production or shared database.
With Compose, create the isolated test database once using
`docker compose exec postgres createdb -U peek peek_test`.

The current standard commands are:

```text
dev:   cd backend && mvn spring-boot:run
test:  cd backend && mvn test
build: cd backend && mvn package
full PostgreSQL verification: cd backend && mvn verify
```

There is no separate lint command yet; Java compilation and tests are the
current gates. The API contracts and examples are in `docs/backend_api.md`.
