# ADR-0009: Danh sách sản phẩm của admin đọc bảng inventory bằng LEFT JOIN (ngoại lệ hẹp của ADR-0001)

- **Status:** Accepted
- **Date:** 2026-10-28

## Context
Trang quản trị sản phẩm cần sắp xếp theo `available,asc` và lọc `lowStock` (tồn kho khả dụng <= ngưỡng) **trên toàn bộ tập kết quả, có phân trang**. `available = quantity - reserved` nằm ở bảng `inventory`, thuộc module `inventory`; `products` thuộc module `product`. ADR-0001 cấm module này đọc bảng của module khác.

Nếu chỉ gọi `InventoryService` rồi sắp xếp/lọc ở tầng ứng dụng thì phải tải toàn bộ sản phẩm (hoặc lấy từng trang rồi lọc, làm sai `totalItems` và thứ tự giữa các trang).

## Decision
Chỉ truy vấn danh sách admin của `product` (`ProductMapper.adminSearch` / `adminCount`) được phép `LEFT JOIN inventory ON inventory.product_id = products.id` (quan hệ 1:1), với các ràng buộc:
- **Chỉ đọc**, và **chỉ để sắp xếp và lọc** (`ORDER BY` / `WHERE` trên `COALESCE(quantity - reserved, 0)`); không bao giờ ghi vào `inventory`.
- Số liệu tồn kho trả về cho client vẫn lấy qua `InventoryService.findByProductIds` (một truy vấn theo lô); mapper của `product` không trả cột của `inventory`.
- Module `inventory` vẫn là chủ sở hữu duy nhất của bảng: mọi thao tác ghi đi qua `InventoryService`. Mọi thay đổi schema của `inventory` phải kiểm tra truy vấn này.
- Quy ước khoá toàn cục (xem Javadoc `InventoryService`): luôn khoá hàng `products` trước rồi mới `inventory`; truy vấn danh sách chỉ đọc, không khoá nên không ảnh hưởng thứ tự này.
- Ngoại lệ **chỉ áp dụng** cho truy vấn này. Truy vấn khác của `product` không được join `inventory`; module khác không được mượn ngoại lệ này.

## Alternatives considered
- **Lọc/sắp xếp ở tầng ứng dụng qua `InventoryService`**: đúng ranh giới nhưng phân trang sai hoặc phải tải toàn bộ bảng.
- **Nhân bản `available` sang bảng `products`**: hai nguồn sự thật, phải đồng bộ trong từng thao tác giữ chỗ; rủi ro cao cạnh flash-sale.
- **Thêm hàm "tìm id theo tồn kho" vào `InventoryService`**: phải truyền danh sách id rất lớn qua lại giữa hai module, và vẫn không kết hợp được với các bộ lọc khác trong một truy vấn.

## Consequences
- Một coupling schema có chủ đích giữa `product` và `inventory`, giới hạn ở một truy vấn read-only.
- Sắp xếp/lọc tồn kho chính xác và phân trang đúng trong một truy vấn.
- Nếu `inventory` được tách khỏi cơ sở dữ liệu của monolith, truy vấn này phải được viết lại.
