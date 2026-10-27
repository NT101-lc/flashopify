# ADR-0001: Kiến trúc modular monolith

- **Status:** Accepted
- **Date:** 2026-10-27

## Context
Flashopify có 9 domain: auth, product, inventory, flashsale, order, payment, notification, ai, admin. Dự án do một người làm trong khoảng 4 tuần (SRS 2.3). Hệ thống vẫn phải scale ngang được (HNFR-03) và dễ bảo trì, coverage service ≥ 70% (HNFR-05). Mục tiêu học tập là hiểu ranh giới domain và giao tiếp bằng event, nhưng không muốn mất thời gian vận hành nhiều service.

## Decision
Dùng **một ứng dụng Spring Boot duy nhất, chia module theo domain**:
- Mỗi module có `controller/ service/ mapper/ domain/ dto/`. Mapper và bảng là riêng của module.
- Module chỉ gọi nhau qua **service interface** (gọi đồng bộ) hoặc **domain event** (bất đồng bộ qua Kafka/outbox, xem ADR-0003).
- Một MySQL dùng chung, nhưng mỗi module chỉ đọc/ghi bảng của mình. Muốn join chéo module thì gọi qua service.
- App stateless (JWT + Redis), chạy nhiều instance sau load balancer.
- Ranh giới module được kiểm tra trong review và có thể thêm ArchUnit test sau.

## Alternatives considered
- **Microservices**: phải có service discovery, gateway, nhiều DB, distributed tracing và saga phân tán. Một người không vận hành nổi trong 4 tuần; độ trễ mạng giữa các service còn làm khó đạt p99 < 200 ms.
- **Monolith phân lớp truyền thống** (controller/service/repository toàn cục): nhanh lúc đầu nhưng các domain dính chặt nhau, về sau khó tách và không luyện được tư duy ranh giới domain.
- **Multi-module Maven** (mỗi domain một artifact): ranh giới chặt hơn nhưng build phức tạp. Để dành, khi cần thì chuyển sang sau.

## Consequences
- Deploy, debug và test đơn giản: chỉ một artifact, transaction cục bộ vẫn dùng được.
- Ranh giới phụ thuộc vào kỷ luật của người viết code, cần review hoặc ArchUnit.
- Có thể tách một module (ví dụ flashsale) thành service riêng mà không phải viết lại nhiều, vì module đã giao tiếp qua interface/event.
- Scale theo cả khối: endpoint flash-sale nặng thì toàn app phải scale theo.
