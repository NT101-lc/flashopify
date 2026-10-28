# ADR-0011: Seed job nằm trong module `admin`, chạy bằng Spring profile `seed`

- **Status:** Accepted
- **Date:** 2026-10-28

## Context
ADR-0006 quyết định seed sản phẩm và ảnh từ DummyJSON, nhưng để mở hai hướng: Spring profile `seed` hoặc script riêng trong `scripts/seed/`. FLS-011 cần chọn một. Seed phải ghi vào bảng của module `product` (và `inventory`), mà quy tắc ADR-0001 cấm đụng mapper/bảng của module khác. Seed cũng cần tái dùng cấu hình datasource và object storage đã có.

## Decision
- Logic seed nằm trong backend, package `com.flashopify.admin.seed`, mọi bean gắn `@Profile("seed")`. Module `admin` là nơi ghép các module khác, nên hợp với vai trò "điều phối".
- `admin.seed` **không bao giờ dùng mapper hay bảng của module khác**. Mọi ghi dữ liệu đi qua service công khai: `ProductImportService` (module `product`: `upsertCategory`, `existsBySourceId`, `importProduct`, `countExternalImageUrls`), trong đó `product` tự gọi `InventoryService.initialize`. `importProduct` chạy trong một transaction cho mỗi sản phẩm (product + images + inventory), lỗi bất kỳ thì rollback cả sản phẩm.
- Chạy bằng `bash scripts/seed/seed.sh`, chỉ là launcher mỏng: nạp `infra/docker/.env` nếu có rồi chạy `./mvnw spring-boot:run -Dspring-boot.run.profiles=local,seed`. Profile `seed` đặt `web-application-type: none` (không mở cổng HTTP), `SeedRunner` chạy các bước rồi thoát với mã 0 (thành công) hoặc 1.
- Các bước là bean `SeedStep` theo `@Order`; thêm bước mới (FLS-012 thêm `UserSeeder` sau `ProductSeeder`) chỉ cần thêm một bean.
- Idempotent theo `products.source_id` (unique): sản phẩm đã có bị bỏ qua và không tải lại ảnh; hai lần seed đồng thời thì khoá unique quyết định, bên thua coi như "đã có". Slug/SKU trùng với sản phẩm khác được làm duy nhất bằng hậu tố (`-{sourceId}`, `DJ-{sourceId}`).
- Ảnh được tải và upload **trước** transaction của sản phẩm (không giữ transaction trong lúc I/O mạng), key cố định `products/src-{sourceId}/…` nên chạy lại chỉ ghi đè. Lỗi tải: thử lại 3 lần (backoff nhân đôi từ 200 ms), sau đó dùng ảnh placeholder `products/placeholder.png` (sinh bằng `BufferedImage`, không commit file nhị phân) và log WARN. Cuối job đếm URL còn chứa `dummyjson.com`; khác 0 thì log ERROR và thoát mã 1.
- FLS-012 (admin và 1.000 customer) dùng **một hash BCrypt dùng chung** cho mọi customer giả để seed nhanh (tính hash một lần thay vì 1.000 lần); mật khẩu đó chỉ là dữ liệu test local.
- Bước sinh embedding của ADR-0006 (mục 5) **ngoài phạm vi** FLS-011; làm ở FLS-039 (`ai` module).

## Alternatives considered
- **Script ngoài (Node/Python) gọi API admin hoặc ghi thẳng SQL**: thêm công cụ ngoài stack; ghi SQL trực tiếp bỏ qua service và invariants, gọi API admin cần token và đi qua cache/event không cần thiết.
- **Đặt seed trong module `product`**: product không được biết `inventory`/`ai` ngoài service interface, và FLS-012 còn phải seed `auth`; module điều phối `admin` hợp hơn.
- **Seed trong Flyway migration**: cần mạng lúc migrate, lẫn dữ liệu demo vào schema production.

## Consequences
- Chạy seed cần infra đang chạy và internet (lần đầu khoảng 1,5 phút cho 194 sản phẩm và 668 ảnh).
- `product` có thêm API nhập liệu hàng loạt công khai; không có endpoint HTTP nào dùng nó.
- Nếu một sản phẩm lỗi sau khi ảnh đã upload, object mồ côi còn lại nhưng lần chạy sau ghi đè đúng key đó.
