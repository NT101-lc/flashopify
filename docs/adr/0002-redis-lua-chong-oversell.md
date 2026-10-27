# ADR-0002: Trừ kho flash-sale bằng Redis + Lua script để chống oversell

- **Status:** Accepted
- **Date:** 2026-10-27

## Context
Endpoint `POST /flash-sales/{itemId}/purchase` phải chịu ≥ 2.000 req/s với p99 < 200 ms (HNFR-01), và bảo đảm **0 oversell** khi 10.000 request tranh 100 sản phẩm (HNFR-02, TC-01). Mỗi lượt mua phải kiểm tra cùng lúc 3 điều kiện: đang trong khung giờ, user chưa vượt `limitPerUser`, và còn hàng (TC-02, TC-04). Tất cả request dồn vào **cùng một dòng** `flash_sale_items`, tức một hot row.

## Decision
- Trước giờ mở bán 5 phút, job warm-up nạp `fs:stock:{itemId}`, giờ mở/đóng và giới hạn vào Redis.
- Mỗi lượt mua chạy **một Lua script** (atomic trên Redis), làm lần lượt: kiểm tra giờ → kiểm tra `fs:bought:{itemId}` (hash userId → số đã mua) → kiểm tra stock > 0 → `DECRBY` stock và `HINCRBY` bought. Script trả mã `OK | SOLD_OUT | LIMIT_EXCEEDED | NOT_ACTIVE`.
- Thành công thì gửi message tạo đơn lên Kafka và trả 202. MySQL chỉ ghi đơn ở phía consumer.
- Hoàn kho (đơn FAILED/EXPIRED/CANCELLED) cũng dùng Lua: `INCRBY` stock và giảm bought.
- Có job đối soát Redis–MySQL mỗi 10 phút. Nếu Redis mất dữ liệu: khoá chiến dịch và nạp lại từ MySQL (OR-11, OR-12).

## Alternatives considered
- **Pessimistic lock DB** (`SELECT … FOR UPDATE`): đúng tuyệt đối nhưng mọi request xếp hàng trên một row lock, throughput chỉ khoảng vài trăm TPS, connection pool cạn và p99 vọt lên hàng giây.
- **Optimistic lock DB** (`UPDATE … WHERE version = ?` hoặc `stock > 0`): không giữ lock lâu, nhưng khi tranh chấp cao thì phần lớn request thất bại và phải retry. DB vẫn nhận toàn bộ tải ghi. Phù hợp cho kho thường (HFR-03), không hợp với flash-sale.
- **Redisson distributed lock** quanh đoạn đọc-kiểm-ghi: thêm vòng lock/unlock qua mạng, các request vẫn bị tuần tự hoá, còn có rủi ro lock hết hạn giữa chừng hoặc lỗi failover. Lua script đã atomic sẵn nên lock là thừa.

## Consequences
- Kiểm tra và trừ kho mất dưới 1 ms trên Redis, DB không còn là nút cổ chai, đạt được mục tiêu TPS.
- Redis trở thành nguồn sự thật **tạm thời** của kho flash-sale. Cần có đối soát và quy trình phục hồi, nếu không kho Redis và MySQL sẽ lệch nhau.
- Khi Redis chạy cluster, các key của một item phải dùng chung hash tag `{itemId}` thì Lua mới chạy được.
- Logic nghiệp vụ nằm một phần trong Lua nên cần integration test riêng bằng Testcontainers Redis.
