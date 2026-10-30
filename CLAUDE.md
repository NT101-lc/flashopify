# CLAUDE.md

## Project overview

Flashopify is a learning project: an e-commerce platform with flash-sale support, built to practice Java backend, system architecture and applied AI.
It is a modular monolith backend with a React storefront and admin UI modelled after Shopify's layout (Dawn theme + Shopify admin).
The hard problem is the flash sale: high-concurrency stock reservation without overselling, plus reliable async order processing.
Requirements live in [docs/srs.md](docs/srs.md); design lives in [docs/architecture.md](docs/architecture.md).

## Tech stack

- **Backend:** Java 17, Spring Boot 3, MyBatis, Lombok, MySQL 8, Redis 7, Kafka, Flyway, Spring AI + pgvector
  (Java 17 + Lombok + MyBatis are fixed; no JPA/Hibernate, no other Java version.)
- **Frontend:** React 18 + TypeScript (strict), Vite, React Router, TanStack Query, Tailwind + shadcn/ui
- **Infra:** Docker Compose, MinIO (local) / S3 + CloudFront, GitHub Actions, AWS
- **Seed data:** products + images from DummyJSON; images are downloaded into object storage

## Repo layout

```
backend/        Spring Boot modular monolith
frontend/       React + Vite app (storefront + admin)
infra/docker/   Docker Compose and service configs
infra/k6/       Load test scripts (flash-sale scenarios)
scripts/seed/   DummyJSON import + image upload scripts
docs/           SRS, architecture, roadmap, conventions, ADRs, API specs
.claude/        Agents, commands and settings for Claude Code
```

## Backend rules

- Modular monolith by domain: `auth`, `product`, `inventory`, `flashsale`, `order`, `payment`, `notification`, `ai`, `admin`.
- Each module has `controller/`, `service/`, `mapper/`, `domain/`, `dto/`.
- Modules talk to each other **only** through a service interface or a domain event. Never call another module's mapper or touch its tables.
- MyBatis: always use `#{}` parameters. Never build SQL by string concatenation or `${}` with user input.
- Every API that creates an order or performs a purchase **requires** an `Idempotency-Key` header.
- All errors return:
  ```json
  { "code": "STRING_CODE", "message": "human readable", "traceId": "...", "timestamp": "ISO-8601" }
  ```
- Schema changes go through Flyway migrations only.

## AI constraints

- No LLM call in the checkout, stock-deduction or payment paths. These flows must be 100% deterministic.
- The in-app AI assistant (if any) may only call read-only tools (`searchProducts`, `getOrderStatus`, FAQ). It must never create or modify orders.

## Frontend rules

- Organize by feature: `catalog`, `cart`, `checkout`, `flash-sale`, `account`, `admin`.
- API types are generated from the OpenAPI spec (`docs/api/`). Do not hand-write DTO types.
- Components never call `fetch` directly; use API client + TanStack Query hooks per feature.
- TypeScript `strict` mode; no `any` without a justifying comment.

## Testing

- Backend: JUnit 5 + Mockito for unit tests, Testcontainers (MySQL, Redis, Kafka) for integration tests.
- Frontend: Vitest + React Testing Library.
- E2E: Playwright.
- Coverage target: service layer ≥ 70%.

## Workflow

Models: **orchestrator** and reviewers = Opus, **coder** = Sonnet, **tester** = Haiku.

The orchestrator classifies every task first and writes the type in the plan. When unsure, it is **CORE**.

- **SIMPLE** — CRUD, auth, DTO/MyBatis mapper, seed data, config, pure UI.
  One coder does it end to end → deterministic gate → hand to the user for review. No separate tester, no Opus review.
- **CORE** — anything touching flash sale: stock deduction (Redis/Lua), oversell prevention, order idempotency, message queue/consumers, rate limiting, per-user purchase limits, Redis↔DB transactions.
  Full pipeline, sequential:
  1. orchestrator writes the plan (steps, files, acceptance criteria, mandatory test cases `TC-n`).
  2. coder implements.
  3. Deterministic gate (`scripts/gate.sh`: compile, test, spotless/checkstyle). Fail → back to coder; never go to LLM review on a red gate.
  4. tester implements exactly the plan's `TC-n` list (no case dropped) and runs it.
  5. Three independent reviewers in parallel: `reviewer-concurrency`, `reviewer-security`, `reviewer-performance`. Findings are BLOCKER / MAJOR / MINOR.
  6. orchestrator merges findings: any BLOCKER → no merge; MAJOR → fix; MINOR → report only.
  7. At most 2 fix → review rounds. BLOCKER still open after round 2 → stop and report to the user.

`/ship` picks the branch automatically and ends with a short report.

### Parallel work

- Frontend and backend run in parallel only **after** the API contract is settled in `docs/api/openapi.yaml`. No contract yet → contract first.
- Never let two agents modify the `order` or `inventory` module at the same time.
- Every agent running in parallel works in its own git worktree.

Agents live in `.claude/agents/`, commands in `.claude/commands/`.

## Commands

Run from the repo root unless the command starts with `cd`.

| Task                | Command |
|---------------------|---------|
| Gate (deterministic)| `bash scripts/gate.sh` |
| Coverage check      | `cd backend && ./mvnw verify` (fails if any `*.service` package < 70% lines; report in `backend/target/site/jacoco`) |
| Start infra         | `docker compose -f infra/docker/docker-compose.yml up -d --wait` |
| Stop infra          | `docker compose -f infra/docker/docker-compose.yml down` (add `-v` to wipe data) |
| Run backend         | `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |
| Backend unit tests  | `cd backend && ./mvnw test` |
| Backend all tests   | `cd backend && ./mvnw verify` (unit + Testcontainers `*IT`, needs Docker) |
| Backend format      | `cd backend && ./mvnw spotless:apply` |
| Lint API spec       | `npx --yes @redocly/cli@2.64.1 lint docs/api/openapi.yaml` |
| Generate API types  | `cd frontend && npm run gen:api` (after editing `docs/api/openapi.yaml`) |
| Run frontend        | `cd frontend && npm run dev` (http://localhost:5173, proxies `/api` to :8080) |
| Frontend lint       | `cd frontend && npm run lint && npm run typecheck` |
| Frontend tests      | `cd frontend && npm test` |
| Frontend build      | `cd frontend && npm run build` |
| E2E tests           | `cd frontend && npx playwright install chromium && npm run e2e` |
| Seed data           | `bash scripts/seed/seed.sh` (needs infra up + internet); also needs `SEED_ADMIN_EMAIL`, `SEED_ADMIN_PASSWORD`, `SEED_CUSTOMER_PASSWORD` in `infra/docker/.env` |
| Load test (k6)      | TBD     |

## Do

- Follow [docs/conventions.md](docs/conventions.md) for naming, packages, API style and git.
- Record significant design decisions as ADRs in `docs/adr/` (copy `0000-template.md`).
- Keep changes small and tested.
- Ask before deviating from the architecture.

## Don't

- Don't commit secrets or `.env`; only update `.env.example` with variable names.
- Don't edit a Flyway migration that has already run; add a new one.
- Don't add libraries outside the stack above without asking first.
- Don't let modules bypass each other's service interfaces.
- Don't skip `Idempotency-Key` on purchase/order endpoints.
- Don't put an LLM call in checkout, stock deduction or payment.
