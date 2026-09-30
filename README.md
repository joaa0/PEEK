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

The Spring Boot backend is available under `backend/`; the integrated Next.js frontend is under `frontend/`. The layout is:

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

## JEV / AI interpretation

E02 remains deterministic. Its investigation optionally uses the versioned
structured interpretation contract and configurable external LLM adapter, with
static fallback for disabled, failed, timed-out or invalid responses. See
[docs/jev_contract.md](docs/jev_contract.md) for schemas, environment setup,
fictitious-demo restrictions and the frontend/MCP projections. No provider,
model or API credential is hard-coded. External calls are disabled by default.

## Backend development

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

## Repeatable demo fixtures

The executable demo fixtures are versioned in
`postman/PEEK-Manual-QA.postman_collection.json`. Start the backend with the
`demo` profile before running the whole collection:

```text
cd backend
SPRING_PROFILES_ACTIVE=demo mvn spring-boot:run
```

On PowerShell, set `$env:SPRING_PROFILES_ACTIVE = "demo"` before the Maven
command. The first collection group checks health and calls the profile-scoped
demo reset. The reset accepts only a `DEMO-*` or legacy `QA-*` run identifier
and deletes only state linked to the exact demo products `CAM-<runId>`,
`SKU-E02-<runId>`, and `SKU-E04-<runId>`. It never truncates tables or changes
`demo_configuration`. Every scenario creates its product before sending events.
The final group runs reset twice and verifies that the second call deletes nothing.

The collection demonstrates successful inventory and fiscal confirmation,
E01-E04, failed dispatch followed by idempotent retry, missing confirmation,
and late confirmation. E02 and E04 use their own Product Master mappings and
source-shaped requests through simulated adapters. Timeout scenarios call the evaluation endpoint with an
explicit instant after the returned command deadline, so the demo requires no
real waiting. Source-shaped JSON fixture examples used by automated adapter
tests remain under `backend/src/test/resources/fixtures/`.

## Integrated frontend and browser verification

Use `frontend/README.md` to run Next.js and the typed API client.
`docs/frontend_validation.md` records the exact setup and execution order for
Maven, TypeScript, Vitest, the fixture API, the build and browser end-to-end tests.
The browser tests use source-shaped adapter fixtures with fixed time and reset;
Postman is optional. Product distribution to ERP, Mercado Livre and Shopee is
simulated by backend adapters while commands, attempts and mappings are persisted.

## External Codex agent (local MCP)

See [docs/mcp_agent.md](docs/mcp_agent.md) for the local MCP profile, bearer-token
configuration, restricted E01 retry policy, agent audit and reproducible demo.
The server is disabled by default. Start with
`mvn spring-boot:run -Dspring-boot.run.profiles=mcp` from `backend/` after setting
`PEEK_MCP_TOKEN`. Only the PEEK reconciliation engine can verify recovery.

For the combined JEV + Agent demo, start with
`-Dspring-boot.run.profiles=demo,mcp` and configure the backend `PEEK_LLM_*`
environment variables. `node scripts/mcp-e02-demo.mjs prepare --require-jev`
creates fictitious adapter fixtures and checks AVAILABLE through both MCP and
REST before presenting the human-approval prompt. A failure remains visible
with its fallback reason and never approves or resolves an exception.
The Codex MCP example includes the guarded E02 tool. Run its regression checks
with `node --test scripts/mcp-e02-demo.test.mjs` from the repository root.
The latest local checks and provider configuration limits are recorded in
[docs/jev_agent_validation.md](docs/jev_agent_validation.md).
