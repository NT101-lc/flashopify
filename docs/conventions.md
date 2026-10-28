# Conventions

## Naming
- Java: classes `PascalCase`, methods/fields `camelCase`, constants `UPPER_SNAKE_CASE`.
- Suffixes: `XxxController`, `XxxService` / `XxxServiceImpl`, `XxxMapper`, `XxxRequest` / `XxxResponse` (DTO), `XxxEvent`.
- DB: tables and columns `snake_case`, plural table names (`orders`, `order_items`).
- Flyway: `V<yyyyMMddHHmm>__<description>.sql`.
- TypeScript: components `PascalCase.tsx`, hooks `useXxx.ts`, other files `camelCase.ts`.

## Package layout
```
com.flashopify
├── common/                 # shared config, error handling, utils
└── <module>/               # auth, product, inventory, flashsale, order, payment, notification, ai, admin
    ├── controller/
    ├── service/            # public interface + impl
    ├── mapper/             # MyBatis mappers (module-private)
    ├── domain/             # entities, enums, domain events
    └── dto/
```
Frontend: `src/features/<feature>/{components,hooks,api,pages}`, shared code in `src/shared/`.

## API style
- REST, base path `/api/v1`, plural resource nouns, kebab-case paths (`/api/v1/flash-sales`).
- JSON in `camelCase`; timestamps ISO-8601 UTC.
- Pagination: `?page=&size=`; response includes `items`, `page` (0-based), `size`, `totalItems`, `totalPages`.
- Order/purchase endpoints require `Idempotency-Key` header.
- Error body: `{ code, message, traceId, timestamp }` with proper HTTP status.
- OpenAPI specs live in `docs/api/`.

## Git
- Branches: `main` (stable), `feat/<scope>-<short-desc>`, `fix/<scope>-<short-desc>`, `chore/...`, `docs/...`.
- Commits: Conventional Commits — `<type>(<scope>): <summary>`
  - types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`, `build`, `ci`
  - scope: module or feature name, e.g. `feat(flashsale): reserve stock in redis`
- One logical change per commit; PRs into `main` must pass CI.
