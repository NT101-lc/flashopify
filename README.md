# Flashopify

E-commerce platform with flash-sale support, built as a learning project.
Covers Java backend (Spring Boot modular monolith), system architecture for high-concurrency sales, and applied AI.
Storefront and admin UI follow Shopify's layout (Dawn theme + Shopify admin).

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
- [Conventions](docs/conventions.md)
- [Architecture Decision Records](docs/adr/)
- [API specs](docs/api/)
- [Guidance for Claude Code](CLAUDE.md)
