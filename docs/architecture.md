# Architecture

## Overview
High-level view of the system: modular monolith backend, React frontend, data stores and external services.

## Modules
Responsibilities and boundaries of each backend domain module (auth, product, inventory, flashsale, order, payment, notification, ai, admin).

## Data flow: flash-sale
End-to-end path of a flash-sale purchase, from request through stock reservation in Redis to async order creation via Kafka.

## Deployment
How the system runs locally (Docker Compose, MinIO) and on AWS (S3 + CloudFront, CI/CD with GitHub Actions).

## Decisions
Architecture Decision Records (template: [0000-template.md](adr/0000-template.md)).

| ADR | Decision |
|-----|----------|
| [0001](adr/0001-modular-monolith.md) | Modular monolith by domain; modules talk via service interfaces or events |
| [0002](adr/0002-redis-lua-chong-oversell.md) | Flash-sale stock check-and-decrement in Redis with a Lua script |
| [0003](adr/0003-kafka-outbox-pattern.md) | Transactional outbox + polling relay to Kafka, idempotent consumers |
| [0004](adr/0004-mybatis-thay-jpa.md) | MyBatis instead of JPA/Hibernate |
| [0005](adr/0005-tach-postgres-pgvector-cho-ai.md) | Separate PostgreSQL + pgvector for the AI module |
| [0006](adr/0006-seed-du-lieu-tu-dummyjson.md) | One-off seed from DummyJSON, images copied to object storage |
| [0007](adr/0007-jwt-access-refresh-token.md) | JWT access token (15 min) + rotating refresh token in Redis (7 days) |
| [0008](adr/0008-cache-aside-product-detail.md) | Cache-aside for product detail in Redis (static part only), live stock overlay, evict after commit |
