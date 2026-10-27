# ADR-0004: Dùng MyBatis thay vì JPA/Hibernate

- **Status:** Accepted
- **Date:** 2026-10-27

## Context
Các truy vấn quan trọng của hệ thống cần SQL được kiểm soát chính xác:
- Trừ kho có điều kiện: `UPDATE inventory SET reserved = reserved + ?, version = version + 1 WHERE product_id = ? AND version = ? AND quantity - reserved >= ?`.
- Huỷ đơn quá hạn có điều kiện: `UPDATE orders SET status='EXPIRED' WHERE id = ? AND status='PENDING'` (UC-07).
- Relay outbox: `SELECT … FOR UPDATE SKIP LOCKED`.
- Lọc và phân trang sản phẩm, báo cáo doanh thu theo bucket thời gian, thống kê chiến dịch.

Một mục tiêu của dự án là luyện SQL và tối ưu truy vấn trên MySQL. Schema do Flyway quản lý (OR-04), không sinh từ entity.

## Decision
- Dùng **MyBatis** (mybatis-spring-boot-starter), SQL viết trong XML mapper hoặc annotation, mỗi module một mapper riêng (ADR-0001).
- **Chỉ dùng `#{}`**. `${}` chỉ được phép cho giá trị nằm trong whitelist (ví dụ cột sort đã map từ enum `ProductSort`), không bao giờ cho input thô (SEC-10).
- Domain object là POJO + Lombok, map bằng `resultMap`. Không có lazy loading hay dirty checking.
- Phân trang tự viết `LIMIT/OFFSET` cộng câu `COUNT`, trả về `PageResponse`.

## Alternatives considered
- **Spring Data JPA / Hibernate**: CRUD nhanh, nhưng các câu UPDATE có điều kiện, SKIP LOCKED và báo cáo vẫn phải viết native query. Dễ dính N+1 và lazy loading ngoài transaction; dirty checking khiến khó biết SQL nào thực sự chạy, trong khi ở luồng hot cần biết chính xác.
- **jOOQ**: type-safe và mạnh, nhưng phải chạy codegen từ schema, bản thương mại cần license với một số DB, và nằm ngoài stack đã chốt.
- **Spring JdbcTemplate**: nhẹ nhưng phải map thủ công nhiều, không có dynamic SQL tiện như `<if>/<foreach>`.

## Consequences
- SQL rõ ràng, dễ `EXPLAIN` và tối ưu index. Hợp với Flyway (schema là nguồn sự thật).
- Phải viết tay nhiều CRUD hơn. Đổi tên cột thì phải sửa XML (cần test mapper bằng Testcontainers).
- Không có cache cấp 1/2 kiểu Hibernate. Cache sản phẩm làm ở tầng service bằng Redis.
- Reviewer phải kiểm tra `${}` trong mọi mapper.
