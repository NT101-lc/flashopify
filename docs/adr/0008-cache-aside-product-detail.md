# ADR-0008: Cache-aside cho chi tiết sản phẩm, tồn kho đọc trực tiếp

- **Status:** Accepted
- **Date:** 2026-10-28

## Context
`GET /products/{slug}` là trang được đọc nhiều nhất (HNFR-01). Dữ liệu tĩnh của sản phẩm (tên, giá, mô tả, ảnh, danh mục) hiếm khi đổi, nhưng tồn kho thay đổi liên tục khi có đơn và flash-sale. Cache cả hai cùng nhau sẽ làm `availableQuantity` lỗi thời và gây hiểu nhầm cho người mua.

## Decision
- **Cache-aside** trong Redis, key `product:detail:{slug}`, giá trị JSON, TTL `app.catalog.cache.detail-ttl` (mặc định 10 phút).
- Cache **chỉ chứa phần tĩnh** (product, category, images). `availableQuantity` và `inStock` được tính lại từ `InventoryService.availableOf` ở **mọi request**, kể cả khi cache hit (một truy vấn theo khoá chính, rẻ).
- Sản phẩm không tồn tại hoặc không ACTIVE trả 404 và **không** được cache.
- Khi admin sửa/xoá/đổi trạng thái sản phẩm, service gọi `ProductDetailCache.evict(slugs)` **sau khi transaction commit** (tránh request khác nạp lại dữ liệu cũ trước commit). Nếu slug đổi thì xoá cả slug cũ và mới. Việc nối vào admin thuộc FLS-009.
- Redis lỗi (đọc, ghi hoặc xoá) chỉ ghi log WARN và coi như cache miss; catalog không bao giờ trả 500 vì Redis.

## Alternatives considered
- **Cache cả tồn kho**: đơn giản hơn nhưng số tồn kho sai tới TTL. Không chấp nhận được cạnh flash-sale.
- **Spring Cache `@Cacheable`**: ít code, nhưng khó kiểm soát việc bỏ qua lỗi Redis và key theo slug đổi được.
- **Không cache**: đúng nhưng mọi lượt xem đều tốn truy vấn join + ảnh.

## Consequences
- Dữ liệu tĩnh có thể cũ tối đa bằng TTL nếu việc evict thất bại (ví dụ Redis chập chờn đúng lúc admin sửa); chấp nhận được.
- Mỗi lượt xem chi tiết vẫn có một truy vấn inventory nhỏ.
- Flash-sale badge sẽ được overlay theo cách tương tự (không cache) khi module flashsale có mặt.
