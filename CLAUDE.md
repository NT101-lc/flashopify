# CLAUDE.md

## Project overview

Flashopify is a learning project: an e-commerce platform with flash-sale support, built to practice Java backend, system architecture and applied AI.
It is a modular monolith backend with a React storefront and admin UI modelled after Shopify's layout (Dawn theme + Shopify admin).
The hard problem is the flash sale: high-concurrency stock reservation without overselling, plus reliable async order processing.
Requirements live in [docs/srs.md](docs/srs.md); design lives in [docs/architecture.md](docs/architecture.md).

## Tech stack

- **Backend:** Java 17, Spring Boot 3, MyBatis, Lombok, MySQL 8, Redis 7, Kafka, Flyway, Spring AI + pgvector
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

1. **orchestrator** breaks the task into a plan.
2. **coder** implements the plan.
3. **tester** writes and runs tests.
4. **reviewer** reviews the diff against these rules.
5. `/ship` runs tests → review → commit (Conventional Commits).

Agents live in `.claude/agents/`, commands in `.claude/commands/`.

## Commands

| Task                | Command |
|---------------------|---------|
| Start infra         | TBD     |
| Run backend         | TBD     |
| Backend tests       | TBD     |
| Run frontend        | TBD     |
| Frontend tests      | TBD     |
| E2E tests           | TBD     |
| Seed data           | TBD     |
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
