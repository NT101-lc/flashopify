# ADR-0006: Seed sản phẩm và ảnh từ DummyJSON vào object storage

- **Status:** Accepted
- **Date:** 2026-10-27

## Context
Storefront theo phong cách Shopify cần dữ liệu trông thật: tên, mô tả, giá, brand, rating và nhiều ảnh mỗi sản phẩm, đủ để demo lọc, tìm kiếm, RAG và load test. Tự nhập tay thì tốn thời gian; dữ liệu sinh ngẫu nhiên (Faker) lại không có ảnh khớp với sản phẩm. Ảnh phải phục vụ qua CDN của mình (OR-06, HNFR-07), không hotlink. Sau khi seed, hệ thống không được phụ thuộc DummyJSON nữa (UC-12).

## Decision
- Viết một **seed job** (Spring profile `seed`, hoặc script trong `scripts/seed/`) chạy một lần:
  1. Lấy `/products/categories` để tạo `categories`.
  2. Phân trang `/products?limit=&skip=` (khoảng 194 sản phẩm).
  3. Tải thumbnail và các ảnh, upload lên MinIO/S3 dưới key `products/{id}/…`, ghi URL CDN vào `products.thumbnail_url` và `product_images`.
  4. Ghi `product`, `product_images` và `inventory` (stock lấy từ nguồn) trong một transaction cho mỗi sản phẩm.
  5. Sinh embedding cho AI (ADR-0005).
- **Idempotent** theo `products.source_id` (unique): chạy lại thì bỏ qua sản phẩm đã có.
- Ảnh tải lỗi thì retry 3 lần, sau đó dùng ảnh placeholder và log cảnh báo.
- Giá giữ USD hoặc quy đổi VND theo tỉ giá cố định trong cấu hình. Sinh thêm 1 admin, 1.000 customer và 1 chiến dịch mẫu.

## Alternatives considered
- **Hotlink ảnh DummyJSON**: nhanh nhưng phụ thuộc bên thứ ba, không đo được hiệu năng CDN của mình, và không luyện được luồng object storage.
- **Faker + ảnh placeholder**: không cần mạng, nhưng ảnh không khớp sản phẩm, UI và RAG kém thuyết phục.
- **Commit file SQL/ảnh vào repo**: repo nặng hàng trăm MB, khó cập nhật.
- **Crawl sàn TMĐT thật**: vướng vấn đề pháp lý và bản quyền.

## Consequences
- Có dữ liệu demo đẹp, nhất quán, tái tạo được bằng một lệnh.
- Lần seed đầu cần internet và mất vài phút. CI nên dùng fixture nhỏ thay vì gọi DummyJSON.
- Dữ liệu và ảnh DummyJSON chỉ dùng cho học tập, không thương mại (SRS 2.4); cần ghi rõ trong README.
- Cột `source_id` nằm lại trong schema sản xuất. Sản phẩm admin tạo có `source_id = NULL`.
