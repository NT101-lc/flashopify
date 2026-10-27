# Roadmap

## Week 1 — Foundation
- Docker Compose for MySQL, Redis, Kafka, MinIO
- Spring Boot skeleton, module layout, Flyway baseline, global error handling
- Auth module (register/login, JWT)
- Product catalog CRUD + DummyJSON seed with images in object storage

## Week 2 — Flash-sale core
- Inventory module and flash-sale campaign management
- Redis-based stock reservation (atomic, no oversell)
- Purchase API with `Idempotency-Key` and rate limiting
- k6 load test for flash-sale scenarios

## Week 3 — Async & reliability
- Kafka-based async order creation
- Payment module (mock gateway) and order state machine
- Outbox pattern, retries, dead-letter handling, timeout/cancel releasing stock
- Notification module

## Week 4 — Frontend, AI & deploy
- React storefront (Dawn-style) and admin (Shopify admin-style)
- AI module: product search/recommendation with Spring AI + pgvector
- Playwright e2e for critical flows
- GitHub Actions CI/CD and AWS deployment (S3 + CloudFront)
