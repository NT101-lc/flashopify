# ADR-0003: Phát event qua Kafka bằng Transactional Outbox

- **Status:** Accepted
- **Date:** 2026-10-27

## Context
Các event `OrderCreated`, `OrderPaid`, `OrderCancelled` kích hoạt các việc sau: gửi email, hoàn kho, cập nhật thống kê. Thay đổi trạng thái đơn trong MySQL và việc publish event phải **nhất quán**: đơn đã PAID thì event phải được phát, còn transaction rollback thì không được phát event. Lỗi ở notification không được ảnh hưởng luồng mua (HNFR-04). Consumer lỗi phải retry 3 lần rồi đưa vào DLQ (OR-11).

## Decision
- Trong **cùng transaction** ghi đơn/thanh toán, chèn thêm một dòng vào `outbox_events` (eventId, aggregateType, aggregateId, eventType, payload JSON, status NEW).
- Một **relay** (scheduler poll mỗi ~500 ms, `SELECT … FOR UPDATE SKIP LOCKED` theo lô) publish lên Kafka, thành công thì đánh dấu PUBLISHED, lỗi thì tăng `retry_count` và cuối cùng đánh dấu FAILED.
- Đảm bảo **at-least-once**. Consumer chống xử lý trùng nhờ bảng `processed_events (event_id, consumer)`.
- Key Kafka là `orderNo` để giữ thứ tự event của cùng một đơn.
- Riêng message "tạo đơn flash-sale" được gửi thẳng lên Kafka sau Lua (ADR-0002), vì lúc đó chưa có transaction DB nào. Nếu gửi lỗi thì hoàn kho Redis ngay và trả lỗi cho client.

## Alternatives considered
- **Publish trực tiếp sau commit** (`@TransactionalEventListener(AFTER_COMMIT)` rồi `kafkaTemplate.send`): đơn giản, nhưng nếu app crash hoặc Kafka lỗi ngay sau commit thì event mất vĩnh viễn. Ngược lại, gửi trước commit thì có thể phát event cho dữ liệu bị rollback. Không đạt yêu cầu tin cậy.
- **CDC với Debezium** đọc binlog MySQL: không cần polling, độ trễ thấp, ít ảnh hưởng app. Nhưng phải chạy thêm Kafka Connect + Debezium, cấu hình binlog và quản lý connector, quá nặng cho một người trong 4 tuần. Có thể thay relay bằng Debezium sau này mà không đổi schema outbox.
- **Kafka transactions / XA**: không có giao dịch nguyên tử thật giữa MySQL và Kafka, cấu hình phức tạp.

## Consequences
- Không mất event khi crash, và state với event luôn khớp nhau.
- Độ trễ event bằng chu kỳ poll (dưới 1 giây), chấp nhận được với email và thống kê.
- Bảng outbox tăng liên tục, cần job dọn các dòng PUBLISHED cũ.
- Consumer bắt buộc phải idempotent. Cần test cả trường hợp nhận trùng event.
