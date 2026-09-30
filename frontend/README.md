# PEEK frontend

Next.js/TypeScript/Tailwind connected to the Spring Boot API. The home route is `/overview`, with operational cards from the API.
`/exceptions` lists and investigates exceptions; `/products` manages minimal
canonical identity and mappings; `/inventory` compares expected, system and
physical stock with links to products and open inventory exceptions.
`/simulation` executes Normal/E01/E02/E03/E04 through the existing adapters,
commands, evaluation and demo reset endpoints.

`/fiscal` shows read-only fiscal examples and real E03 commands. The six-route
shell uses PEEK branding and supports browser history and direct detail routes.

## Development

Prerequisites: Node.js 22.18+ and npm. In `frontend/`:

```sh
npm ci
cp .env.example .env.local
npm run dev
```

Open `http://localhost:3000`. `PEEK_API_URL` selects the backend (default
`http://127.0.0.1:8080`). Next.js proxies `/api/v1/*` on the server, avoiding
cross-origin browser configuration. Restart Next.js after changing the URL.
The browser never calls a third-party LLM or receives an LLM credential.
An unavailable backend produces a recoverable error, not a local catalog.

### Work without PostgreSQL

Start the explicit, read-only fixture API in a separate terminal:

```sh
npm run dev:fixtures
```

Then run Next.js with `PEEK_API_URL=http://127.0.0.1:8081`. This serves versioned
fictitious contract responses from `src/test/fixtures.ts`. Mutations return 405
with a fixture-mode explanation; use Spring Boot for real persistence and
orchestration. Production/default mode never selects these fixtures implicitly.

## Verification, in order

```sh
npm ci
npm run format:check
npm run lint
npm test
npm run test:fixtures
npm run build
```

`lint` is strict TypeScript checking. Prettier configuration is committed.
Vitest covers HTTP contracts, API failures, filters, investigation, missing
context, stocks, fallback, explicit actions and mappings. The fixture test
checks a local HTTP server independent of PostgreSQL.

For the actual Spring Boot/PostgreSQL browser tests, follow
[`docs/frontend_validation.md`](../docs/frontend_validation.md). Then:

```sh
npx playwright install --with-deps chromium
npm run test:e2e
```

The operational Playwright tests start Next.js, use the real backend, and replay source-shaped adapter fixtures, evaluates fixed instants and resets
its own demo namespace. Default backend/frontend URLs can be changed using
`PEEK_BACKEND_URL` / `PEEK_FRONTEND_URL` for the test client; set `PEEK_API_URL`
consistently for the Next.js proxy. Run build and browser tests sequentially.
For `npm run start`, set `PEEK_API_URL` before `npm run build` as well: the
production rewrite destination is recorded during the build.

## Actions and contracts

Product/mapping edits send `If-Match-Version`. Conflicts preserve the form and
show the API response. Retry is offered only for eligible command statuses and
requires confirmation. An uncertain API response retains the same idempotency
key for resubmission. Resolution requires a note and is persisted by the API;
retry does not resolve the exception automatically.

Product distribution supports ERP, Mercado Livre and Shopee simulated adapters.
Destination commands keep independent status, returned external identity,
failure evidence and immutable attempts. Evidence uses the same timeline
component as exception investigation; propagation failure does not invent E05.

The backend returns the static recommendation and an optional top-level `jev`
interpretation on E02 investigation. The versioned contract in `contracts.ts`
renders the main hypothesis, evidence-linked rationale, TypeSafe hypothesis probability, returned Jev model and the PEEK explanation source
and at most two grounded alternatives. FALLBACK preserves the deterministic
conclusion and never invents confidence. The legacy optional flat projection
remains supported. No frontend request goes to a model; backend opt-in and
synthetic-data restrictions are documented in `docs/jev_contract.md`.
## Fiscal mock and shell (#52 / #55)

`/fiscal` is read-only. Four fixed, labelled examples show pending, confirmed,
failed and timed-out commands, with distinct labels, icons and colors. Examples
include attempt history, deadlines, received confirmation time and the external
document occurrence time. They do not create commands or link invented IDs.

The separate demo section reads E03 exceptions through the existing API and
fetches their linked fiscal commands. Both open and resolved exceptions are
included; an exception lifecycle never determines a command's fiscal status.
Missing commands and command-level errors preserve the real investigation link.
This section is scoped to E03 exceptions, not a complete fiscal command ledger.
No issuance, SEFAZ integration, tax rules or fiscal mutations are added.

The sidebar uses the official pink symbol with its background removed, alongside
PEEK. Routes have one active link, including product/investigation detail routes;
browser history updates that active state. Mobile navigation wraps into two
columns and wide fiscal tables scroll within their cards.

Run the isolated frontend browser tests without PostgreSQL:

```sh
npx playwright test e2e/shell-fiscal.spec.ts
```

These three tests intercept HTTP with explicit fixtures. They supplement the
existing real Spring Boot/PostgreSQL scenarios in `e2e/operational.spec.ts`; they
do not claim backend integration coverage. Build and browser runs are sequential.

## Demo simulator

Start Spring Boot with `--spring.profiles.active=demo` against an isolated demo
database. `/simulation` keeps one `DEMO-*` run ID in sessionStorage per browser
tab; each execution resets only that namespace and creates a fresh canonical
product. It uses the backend product creation timestamp for scenario time,
supporting both the system and fixed demo clocks, and evaluates beyond actual
command deadlines without waiting. The existing evaluation endpoint evaluates
the entire database, so use a dedicated demo database. Errors may leave partial
session data; the same reset removes it. The read-only fixture server cannot
execute scenarios. E02 success here means detection, not checkpoint acceptance
or exception resolution. The Fiscal card opens the read-only `/fiscal` screen.
