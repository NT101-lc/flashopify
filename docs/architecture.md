# Architecture

## Overview
High-level view of the system: modular monolith backend, React frontend, data stores and external services.

## Modules
Responsibilities and boundaries of each backend domain module (auth, product, inventory, flashsale, order, payment, notification, ai, admin).

## Data flow: flash-sale
End-to-end path of a flash-sale purchase, from request through stock reservation in Redis to async order creation via Kafka.

## Deployment
How the system runs locally (Docker Compose, MinIO) and on AWS (S3 + CloudFront, CI/CD with GitHub Actions).
