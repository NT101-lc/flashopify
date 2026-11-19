# Flashopify

E-commerce platform with flash-sale support, built as a learning project.
Covers Java backend (Spring Boot modular monolith), system architecture for high-concurrency sales, and applied AI.
Storefront and admin UI follow Shopify's layout (Dawn theme + Shopify admin).

## Status

Work is tracked as `FLS-xxx` tasks in the [backlog](docs/backlog.md) and as GitHub issues.

| Milestone | Status |
|-----------|--------|
| Week 1 – Foundation (FLS-001..014) | Done |
| Week 2 – Flash-sale core (FLS-015..026) | Backend core done (FLS-015..019); purchase API, cart, orders, k6 and storefront pages next |
| Week 3 – Async & reliability (FLS-027..038) | Not started |
| Week 4 – Frontend polish, AI & deploy (FLS-039..052) | Not started |

What works today (backend only; the React pages are still placeholders):

- **Auth**: register, JWT login with lockout after 5 failures, rotating refresh tokens, logout, password change, profile, addresses, account deletion; login is rate-limited per IP.
- **Catalog**: public product list (filter, sort, paging) and detail with a Redis cache, categories.
- **Admin products**: CRUD, optimistic-lock stock updates, image upload through presigned URLs to MinIO/S3.
- **Flash-sale campaigns** (`/api/v1/admin/flash-sales`): create, update, cancel; each item reserves its stock in `inventory.reserved`. A scheduler loads the stock into Redis 5 minutes before the start, moves the campaign to RUNNING and ENDED, and writes `sold` back to MySQL.
- **Flash-sale stock in Redis**: atomic Lua purchase (time window → per-user limit → stock) and restore; tested with 1,000 concurrent buyers for 100 units. Not exposed over HTTP until the purchase API (FLS-020).
- **Shared infrastructure**: `@Idempotent` (`Idempotency-Key` header, replay with `Idempotent-Replayed: true`) and `@RateLimited` (Redis token buckets per user and IP, 429 with `Retry-After`).

## Getting started

Prerequisites: JDK 17, Docker (Desktop), Node.js 22.12+ (CI uses 24).

```bash
# 1. Infra: MySQL, PostgreSQL + pgvector, Redis, Kafka + kafka-ui, MinIO, MailHog
docker compose -f infra/docker/docker-compose.yml up -d --wait

# 2. Backend on http://localhost:8080 (Flyway migrates MySQL and PostgreSQL on startup)
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 3. Frontend on http://localhost:5173
cd frontend && npm install && npm run dev
```

Stop / reset the infra:

```bash
# Stop containers, keep data
docker compose -f infra/docker/docker-compose.yml down

# Stop and wipe volumes. WARNING: deletes ALL MySQL, MinIO, PostgreSQL (and Redis/Kafka) data, including seed data
docker compose -f infra/docker/docker-compose.yml down -v
```

Defaults for ports and credentials live in `infra/docker/docker-compose.yml`; override them with
`infra/docker/.env`. Health check: http://localhost:8080/actuator/health.

| Service    | URL / port |
|------------|------------|
| Backend    | http://localhost:8080 (Swagger UI at `/swagger-ui.html`) |
| Frontend   | http://localhost:5173 |
| MySQL      | localhost:3306 |
| PostgreSQL | localhost:5432 |
| Redis      | localhost:6379 |
| Kafka      | localhost:9092 |
| Kafka UI   | http://localhost:8081 |
| MinIO      | http://localhost:9000 (console http://localhost:9001) |
| MailHog    | SMTP localhost:1025, UI http://localhost:8025 |

## Seed data

With the infra up and internet access, import categories, ~194 products and their images from DummyJSON (images are copied into MinIO, nothing is hotlinked):

```bash
bash scripts/seed/seed.sh
```

It is idempotent (re-running skips products already imported by `source_id`) and exits 0 on success. Design: [ADR-0011](docs/adr/0011-seed-job-in-admin-module.md).
DummyJSON data and images are for learning only, not commercial use (see [ADR-0006](docs/adr/0006-seed-du-lieu-tu-dummyjson.md)).

The same command also creates the admin and 1,000 customers (`customer0001.flashopify.local` ...), each with one default address, and writes their credentials to `infra/k6/users.csv` (gitignored; columns `userId,email,password`). Copy `infra/docker/.env.example` to `infra/docker/.env` and set `SEED_ADMIN_EMAIL`, `SEED_ADMIN_PASSWORD` and `SEED_CUSTOMER_PASSWORD` first; the seed fails with a clear message otherwise. Trade-off: all customers share one password and one BCrypt hash (hashed once, cost 10), so a leaked hash exposes every seeded customer. This is acceptable only for local and load-test data, never for real accounts. Set `--app.seed.users.export-tokens=true` to add an `accessToken` column.

## Trying the API

There is no UI yet; use Swagger UI at http://localhost:8080/swagger-ui.html.

1. `POST /api/v1/auth/login` with the admin from `infra/docker/.env`, copy `accessToken`, click **Authorize**.
2. Create a campaign with `POST /api/v1/admin/flash-sales` (start time in the future), then check that the product's `inventory.reserved` grew with `GET /api/v1/admin/products/{id}`.
3. With a start time ~2 minutes ahead, watch the scheduler warm it up and run it (status SCHEDULED → RUNNING → ENDED):

   ```bash
   docker compose -f infra/docker/docker-compose.yml exec redis redis-cli --scan --pattern "fs:*"
   ```

Flash-sale and limit settings live in `backend/src/main/resources/application.yml`:

| Setting | Default | Meaning |
|---------|---------|---------|
| `app.flash-sale.warm-up-lead` | `5m` | Stock is loaded into Redis this long before start; the campaign is read-only from then on |
| `app.flash-sale.scheduler.interval-ms` | `5000` | Lifecycle job period (safe to run on several instances) |
| `app.idempotency.ttl` | `24h` | How long a response is replayed for the same `Idempotency-Key` |
| `app.rate-limit.policies.*` | see file | Per-policy buckets (`capacity`, `refill-period`) for `login`, `flash-sale-purchase`, `ai-chat` |

## Common commands

| Task | Command |
|------|---------|
| Backend unit tests | `cd backend && ./mvnw test` |
| Backend all tests (Testcontainers) | `cd backend && ./mvnw verify` |
| Deterministic gate | `bash scripts/gate.sh` |
| Generate API types | `cd frontend && npm run gen:api` |
| Frontend lint / type-check | `cd frontend && npm run lint && npm run typecheck` |
| Frontend tests | `cd frontend && npm test` |
| Frontend build | `cd frontend && npm run build` |
| E2E | `cd frontend && npx playwright install chromium && npm run e2e` |

## Docs

- [Software Requirements (SRS)](docs/srs.md)
- [Architecture](docs/architecture.md)
- [Roadmap](docs/roadmap.md)
- [Backlog](docs/backlog.md)
- [Conventions](docs/conventions.md)
- [Architecture Decision Records](docs/adr/)
- [API specs](docs/api/)
- [Guidance for Claude Code](CLAUDE.md)
