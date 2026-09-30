# PEEKio frontend

Next.js/TypeScript/Tailwind connected to the Spring Boot API. The primary route
is `/exceptions`; `/products` manages minimal canonical identity, mappings and
operational context. Investigation contains stock and order context; there are
no separate order/inventory management pages.

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

Playwright starts Next.js, uses the real backend (not browser request mocks),
replays source-shaped adapter fixtures, evaluates fixed instants and resets
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
