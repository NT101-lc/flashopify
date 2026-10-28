# Backlog

Mỗi task 0,5–1 ngày công, merge độc lập (một PR / task). ID dạng `FLS-xxx`. Liên kết tham chiếu [SRS](srs.md) (HFR/HNFR/SEC/UC/OR/TC), [OpenAPI](api/openapi.yaml) và [ADR](adr/).

Label: `backend` · `frontend` · `infra` · `docs` · `test`. Ước lượng: `0.5d` / `1d`.

| Milestone | Số task | Tổng ước lượng |
|---|---|---|
| Week 1 – Foundation | 14 | 11,5d |
| Week 2 – Flash-sale core | 12 | 10,5d |
| Week 3 – Async & reliability | 12 | 11d |
| Week 4 – Frontend polish, AI & deploy | 14 | 11,5d |
| **Tổng** | **52** | **44,5d** |

> Frontend được rải từ Week 1 (scaffold) đến Week 4 (polish), thay vì dồn hết vào Week 4 như roadmap.md, để mỗi tuần có thể demo end-to-end.

---

## Week 1 – Foundation

### FLS-001 · Docker Compose cho môi trường local
**Label:** infra · **Ước lượng:** 1d · **Phụ thuộc:** — · **Liên kết:** SRS 2.3, OR-04, 6.2
Một lệnh `docker compose up` chạy đủ hạ tầng local: MySQL 8, Redis 7, Kafka (KRaft), MinIO, PostgreSQL 16 + pgvector, Mailhog. Có healthcheck cho từng service.
- [x] `infra/docker/compose.yaml` có đủ 6 service, healthcheck đều xanh _(file thực tế: `docker-compose.yml`, thêm kafka-ui)_
- [ ] MinIO tự tạo bucket `flashopify`; Postgres bật extension `vector`
- [x] `.env.example` bổ sung biến còn thiếu; không commit `.env` _(`infra/docker/.env.example`)_
- [ ] README ghi lệnh khởi động/dừng/xoá volume

### FLS-002 · Skeleton Spring Boot modular monolith
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-001 · **Liên kết:** HNFR-03, HNFR-05, OR-09, ADR-0001
Tạo project Maven Java 17 / Spring Boot 3 với 9 package module (`controller/service/mapper/domain/dto`), profile `local/test/prod`, Actuator, springdoc phục vụ `docs/api/openapi.yaml`.
- [x] `./mvnw verify` chạy xanh; app khởi động với profile `local`
- [x] `/actuator/health` trả UP (MySQL, Redis)
- [ ] ArchUnit test: module không truy cập `mapper` của module khác
- [ ] Swagger UI hiển thị spec

### FLS-003 · Flyway baseline + cấu hình MyBatis
**Label:** backend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-002 · **Liên kết:** OR-04, ADR-0004, ADR-0005
Đưa `V1__init.sql` (MySQL) và `V1__ai_init.sql` (Postgres) vào `db/migration`, cấu hình 2 datasource và MyBatis (camelCase mapping, type handler cho enum).
- [x] App khởi động tự migrate cả 2 DB
- [x] Integration test Testcontainers xác nhận đủ bảng
- [x] Datasource Postgres tách riêng, chỉ module `ai` dùng

### FLS-004 · Xử lý lỗi chung + traceId
**Label:** backend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-002 · **Liên kết:** OR-08, SRS 6.4, SEC-10, SEC-11
`@RestControllerAdvice` trả `ErrorResponse { code, message, traceId, timestamp, fieldErrors }`, enum `ErrorCode` khớp OpenAPI; filter sinh `X-Trace-Id` và đưa vào MDC.
- [x] Lỗi Bean Validation → 400 `VALIDATION_ERROR` có `fieldErrors`
- [x] `BusinessException(ErrorCode)` map đúng HTTP status
- [x] Mọi response có header `X-Trace-Id`; log có traceId, không log mật khẩu/token
- [ ] Unit test cho advice

### FLS-005 · Đăng ký + đăng nhập JWT + khoá tài khoản
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-003, FLS-004 · **Liên kết:** HFR-01, UC-01, UC-02, SEC-01, SEC-05, TC-10, ADR-0007
`POST /auth/register`, `POST /auth/login`: BCrypt cost 10, access token 15 phút, đếm sai mật khẩu, khoá 15 phút sau 5 lần.
- [x] Email trùng → 409 `EMAIL_ALREADY_EXISTS`
- [x] Sai mật khẩu → 401; lần thứ 5 → 423 `ACCOUNT_LOCKED` (TC-10)
- [x] Đăng nhập thành công reset bộ đếm, trả `TokenResponse`
- [x] Coverage service auth ≥ 70%

### FLS-006 · Refresh token, logout, đổi mật khẩu
**Label:** backend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-005 · **Liên kết:** SEC-02, SEC-03, ADR-0007
Refresh token 7 ngày lưu Redis, rotate mỗi lần dùng, phát hiện reuse; `POST /auth/refresh`, `POST /auth/logout`, `PUT /me/password`.
- [x] Refresh token cũ dùng lại → 401 và thu hồi mọi phiên của user
- [x] Logout xoá token khỏi Redis
- [x] Đổi mật khẩu thu hồi toàn bộ refresh token
- [x] Integration test với Testcontainers Redis

### FLS-007 · Phân quyền + hồ sơ + địa chỉ giao hàng
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-005 · **Liên kết:** HFR-01, SEC-04, SEC-07, OR-13
Spring Security filter JWT, role CUSTOMER/ADMIN theo `x-roles`; `GET/PATCH/DELETE /me`, CRUD `/me/addresses`.
- [x] Endpoint ADMIN với token CUSTOMER → 403
- [x] userId luôn lấy từ token; sửa địa chỉ của người khác → 404
- [x] Chỉ một địa chỉ `isDefault`
- [x] Xoá tài khoản: status DELETED, ẩn danh dữ liệu, thu hồi token

### FLS-008 · API catalog công khai + cache
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-003, FLS-004 · **Liên kết:** HFR-02, UC-03, HNFR-01
`GET /categories`, `GET /products` (q, category, brand, giá, rating, sort, page), `GET /products/brands`, `GET /products/{slug}` cache Redis.
- [x] Sort chỉ nhận giá trị whitelist (không `${}` từ input)
- [x] `PageResponse` đúng `page/size/totalItems/totalPages`
- [x] Chi tiết sản phẩm cache Redis, bị xoá khi admin sửa _(evict wired to admin in FLS-009)_
- [x] Sản phẩm INACTIVE/DELETED không xuất hiện

### FLS-009 · Admin CRUD sản phẩm + cập nhật tồn kho
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-007, FLS-008 · **Liên kết:** HFR-02, HFR-03, UC-10, OR-05
`/admin/products` list/create/get/update/delete mềm; `PUT /admin/products/{id}/inventory` dùng optimistic lock.
- [ ] SKU/slug trùng → 409
- [ ] Xoá đặt status DELETED; chặn nếu đang trong chiến dịch
- [ ] Sai `version` → 409 `CONCURRENT_MODIFICATION`; không cho `quantity < reserved`
- [ ] Mapper test với Testcontainers MySQL

### FLS-010 · Upload ảnh sản phẩm qua presigned URL
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-001, FLS-009 · **Liên kết:** OR-06, UC-10
Interface `ObjectStorage` (MinIO/S3 qua cấu hình); `upload-url` → PUT trực tiếp → `POST images` lưu URL CDN; reorder và xoá ảnh.
- [ ] Chỉ JPG/PNG/WebP ≤ 5 MB; tối đa 8 ảnh/sản phẩm
- [ ] `POST images` kiểm tra object tồn tại, nếu không → 400 `OBJECT_NOT_FOUND`
- [ ] Reorder yêu cầu đủ mọi imageId
- [ ] Đổi MinIO ↔ S3 chỉ bằng cấu hình

### FLS-011 · Seed sản phẩm và ảnh từ DummyJSON
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-010 · **Liên kết:** HFR-12, UC-12, OR-07, ADR-0006
Job profile `seed`: import category, ~194 sản phẩm, tải ảnh lên object storage, ghi product/images/inventory trong transaction.
- [ ] Chạy lại không tạo trùng (theo `source_id`)
- [ ] Ảnh lỗi retry 3 lần rồi dùng placeholder + log cảnh báo
- [ ] Không còn URL `dummyjson.com` trong DB sau khi seed
- [ ] Log tổng số sản phẩm/ảnh đã import

### FLS-012 · Seed admin và 1.000 customer
**Label:** backend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-005 · **Liên kết:** OR-07, SRS 7.2
Sinh 1 admin (thông tin từ biến môi trường) và 1.000 customer có địa chỉ, xuất file token/credential test cho k6.
- [ ] Idempotent khi chạy lại
- [ ] Mật khẩu hash BCrypt; không commit credential thật
- [ ] Xuất `infra/k6/users.csv` (bị gitignore)

### FLS-013 · Scaffold frontend React + codegen OpenAPI
**Label:** frontend · **Ước lượng:** 1d · **Phụ thuộc:** — · **Liên kết:** OR-09, HFR-10, SRS 2.3
Vite + React 18 + TS strict, Tailwind + shadcn/ui, React Router, TanStack Query, ESLint/Prettier, cấu trúc `src/features/*`, sinh type + client từ `openapi.yaml`.
- [x] `npm run gen:api` sinh type, không viết tay DTO
- [ ] API client có interceptor đính token và parse `ErrorResponse`
- [x] `npm run lint`, `npm test` (Vitest) chạy xanh
- [x] Theme màu/logo riêng, không dùng asset Shopify

### FLS-014 · CI GitHub Actions
**Label:** infra · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-002, FLS-013 · **Liên kết:** HNFR-05, OR-10
Workflow mỗi PR: backend `mvn verify` (Testcontainers), frontend lint + test, Redocly lint OpenAPI, báo cáo coverage.
- [x] Chạy trên PR và push `main`
- [ ] Fail nếu coverage service < 70%
- [x] Cache Maven/npm

---

## Week 2 – Flash-sale core

### FLS-015 · Admin quản lý chiến dịch flash-sale
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-009 · **Liên kết:** HFR-04, UC-09
CRUD `/admin/flash-sales`: validate giờ, số lượng ≤ tồn kho khả dụng, giữ kho (`reserved`), huỷ chiến dịch hoàn kho.
- [ ] `startTime` quá khứ / `end ≤ start` → 400 kèm `fieldErrors`
- [ ] Vượt tồn kho → 409 `INSUFFICIENT_STOCK`
- [ ] Sửa sau warm-up → 409 `CAMPAIGN_NOT_EDITABLE`
- [ ] Huỷ: status CANCELLED, trả `reserved` phần chưa bán

### FLS-016 · Warm-up Redis và vòng đời chiến dịch
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-015 · **Liên kết:** UC-09, HFR-04, ADR-0002
Scheduler nạp `fs:stock:{itemId}`, giờ mở/đóng, limit vào Redis trước 5 phút; chuyển SCHEDULED → RUNNING → ENDED; đồng bộ `sold` sau chiến dịch.
- [ ] Key dùng hash tag `{itemId}`
- [ ] Warm-up chạy lại không ghi đè kho đang bán
- [ ] Hết giờ: status ENDED, ghi `sold` về MySQL
- [ ] Chạy an toàn khi có nhiều instance (lock lịch / SKIP LOCKED)

### FLS-017 · Lua script trừ kho flash-sale
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-016 · **Liên kết:** HNFR-02, UC-05, TC-01, TC-02, TC-04, ADR-0002
Script atomic kiểm tra giờ → limit user → tồn kho → trừ kho + tăng `bought`; script hoàn kho ngược lại.
- [ ] Trả `OK | SOLD_OUT | LIMIT_EXCEEDED | NOT_ACTIVE`
- [ ] Test đồng thời 1.000 thread / 100 kho → đúng 100 OK (TC-01)
- [ ] 5 request cùng user với limit 1 → 1 OK (TC-02)
- [ ] Hoàn kho không làm kho vượt `totalStock`

### FLS-018 · Hạ tầng Idempotency-Key
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-004 · **Liên kết:** SRS 6.4, TC-03
Annotation `@Idempotent` lưu Redis (userId + key → hash body, trạng thái, response), TTL 24h.
- [ ] Thiếu header → 400 `IDEMPOTENCY_KEY_MISSING`
- [ ] Gửi lại cùng key → trả lại response cũ + `Idempotent-Replayed: true`
- [ ] Cùng key khác body → 409 `IDEMPOTENCY_KEY_CONFLICT`; đang xử lý → 409 `REQUEST_IN_PROGRESS`
- [ ] Unit test cho cả 4 trường hợp

### FLS-019 · Rate limit theo user và IP
**Label:** backend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-004 · **Liên kết:** SEC-06
Token bucket trên Redis (Lua) cấu hình theo endpoint: login, purchase, AI chat.
- [ ] Vượt ngưỡng → 429 `RATE_LIMITED` + `Retry-After`
- [ ] Ngưỡng đổi qua cấu hình, không cần sửa code
- [ ] Test với Testcontainers Redis

### FLS-020 · API mua flash-sale (202 + Kafka)
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-017, FLS-018, FLS-019 · **Liên kết:** UC-05, HFR-04, TC-03, TC-04
`POST /flash-sales/{itemId}/purchase`: rate limit → idempotency → Lua → sinh `orderNo` → gửi message tạo đơn lên Kafka → 202 PROCESSING.
- [ ] Kafka gửi lỗi → hoàn kho Redis và trả 503
- [ ] Gửi lại Idempotency-Key 3 lần → cùng `orderNo` (TC-03)
- [ ] Trước giờ mở bán → 400 `CAMPAIGN_NOT_ACTIVE` (TC-04)
- [ ] Message có schema `eventId, eventType, aggregateId, occurredAt, payload`

### FLS-021 · API flash-sale công khai
**Label:** backend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-016 · **Liên kết:** HFR-04, HFR-10
`GET /flash-sales` và `GET /flash-sales/{itemId}` đọc kho còn lại từ Redis, có `serverTime`, `purchasedByMe` khi có token.
- [ ] Không gọi MySQL cho `remainingStock`
- [ ] Cache ngắn (1–2 giây) cho danh sách
- [ ] Badge flash-sale hiện trên `ProductSummary`

### FLS-022 · API giỏ hàng
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-008, FLS-007 · **Liên kết:** HFR-05, UC-11
`/cart`, `/cart/items`: thêm (cộng dồn), sửa số lượng, xoá, xoá hết; trả `availableQuantity` để cảnh báo.
- [ ] Vượt tồn kho khả dụng → 409 `INSUFFICIENT_STOCK`
- [ ] Sản phẩm không ACTIVE → 404/400 phù hợp
- [ ] Tổng tiền tính bằng `BigDecimal`, trả string 2 chữ số

### FLS-023 · Tạo đơn thường idempotent
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-018, FLS-022 · **Liên kết:** HFR-03, HFR-05, UC-04
`POST /orders`: snapshot địa chỉ và giá, giữ kho bằng optimistic lock (retry giới hạn), đơn PENDING hết hạn sau 15 phút, xoá item khỏi giỏ.
- [ ] Thiếu kho → 409, không giữ kho một phần
- [ ] Unique `(user_id, idempotency_key)` chặn trùng ở DB
- [ ] `orderNo` dạng `OD` + 14 số
- [ ] Test đồng thời không làm `reserved > quantity`

### FLS-024 · Kịch bản k6 cho flash-sale
**Label:** test · **Ước lượng:** 1d · **Phụ thuộc:** FLS-020, FLS-012 · **Liên kết:** HNFR-01, HNFR-02, TC-01, TC-02
Script `infra/k6/` tạo chiến dịch mẫu 100 sản phẩm, 10.000 request / 10 giây, kiểm tra kết quả.
- [ ] Báo cáo TPS, p95/p99, tỉ lệ 202/409/429
- [ ] Sau test: đúng 100 lượt thành công, kho Redis = 0
- [ ] Ghi kết quả vào `docs/` (để so sánh sau tối ưu)

### FLS-025 · Storefront: layout, trang chủ, danh mục, chi tiết sản phẩm
**Label:** frontend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-013, FLS-008 · **Liên kết:** HFR-10, UC-03, OR-01
Header (logo, tìm kiếm, icon giỏ), trang chủ lưới sản phẩm, trang danh mục có lọc trái + sort + phân trang, trang sản phẩm gallery.
- [ ] Filter/sort/page đồng bộ URL query
- [ ] Gallery ảnh lớn + thumbnail, giá gốc gạch khi có flash-sale
- [ ] Dữ liệu qua TanStack Query hooks, không `fetch` trong component

### FLS-026 · Storefront: đăng nhập / đăng ký
**Label:** frontend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-013, FLS-006 · **Liên kết:** UC-01, UC-02
Form login/register, lưu access token trong bộ nhớ, tự refresh khi 401, route guard cho trang cần đăng nhập.
- [ ] Hiển thị lỗi theo `code` (sai mật khẩu, bị khoá)
- [ ] Refresh song song chỉ gọi 1 lần
- [ ] Vitest + RTL cho form

---

## Week 3 – Async & reliability

### FLS-027 · Consumer tạo đơn flash-sale + DLQ
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-020 · **Liên kết:** UC-05, OR-11
Consume message tạo đơn → ghi đơn FLASH PENDING + outbox `OrderCreated`; idempotent qua `processed_events`; retry 3 lần backoff rồi DLQ.
- [ ] Message trùng không tạo 2 đơn
- [ ] Lỗi 3 lần → DLQ, có log và metric
- [ ] Integration test Testcontainers Kafka

### FLS-028 · Outbox writer + relay publisher
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-003 · **Liên kết:** HNFR-04, ADR-0003
API `OutboxPublisher.add(event)` dùng trong transaction; relay poll `SKIP LOCKED` theo lô, publish Kafka key `orderNo`, đánh dấu PUBLISHED/FAILED; job dọn bản ghi cũ.
- [ ] Rollback transaction → không có event
- [ ] Kafka down → event giữ NEW, publish lại khi Kafka lên
- [ ] Nhiều instance không publish trùng lô

### FLS-029 · State machine đơn hàng + API đơn của tôi
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-023, FLS-027 · **Liên kết:** HFR-05
Chuyển trạng thái có điều kiện (PENDING→PAID/CANCELLED/EXPIRED, PAID→COMPLETED); `GET /orders`, `GET /orders/{orderNo}` (PROCESSING khi chưa ghi DB), `POST /orders/{orderNo}/cancel` hoàn kho.
- [ ] Chuyển sai trạng thái → 409 `INVALID_ORDER_STATE`
- [ ] Huỷ đơn FLASH hoàn kho Redis + giảm `bought`; đơn NORMAL trả `reserved`
- [ ] Ghi outbox `OrderCancelled`
- [ ] Đơn người khác → 404

### FLS-030 · Cổng thanh toán giả lập + tạo phiên thanh toán
**Label:** backend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-029 · **Liên kết:** HFR-06, UC-06
`POST/GET /payments/{orderNo}` tạo payment INITIATED, trang mock gateway gọi webhook có ký HMAC; cấu hình tỉ lệ lỗi / độ trễ / timeout.
- [ ] Đơn không PENDING hoặc quá hạn → 409
- [ ] Đã có payment INITIATED → trả lại (200)
- [ ] Cấu hình SUCCESS/FAILED/timeout qua properties

### FLS-031 · Webhook thanh toán HMAC
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-030, FLS-028 · **Liên kết:** UC-06, SEC-12, TC-05, TC-06
`POST /payments/callback`: xác thực `X-Signature`, idempotent theo `providerTxnId`; SUCCESS → PAID + trừ kho MySQL + `OrderPaid`; FAILED → CANCELLED + hoàn kho.
- [ ] Chữ ký sai → 401 `INVALID_SIGNATURE`
- [ ] Callback trùng → 200 `duplicate: true`, kho trừ 1 lần (TC-05)
- [ ] FAILED → kho Redis tăng lại 1 (TC-06)
- [ ] Sai số tiền → `PAYMENT_AMOUNT_MISMATCH`, không đổi trạng thái

### FLS-032 · Job huỷ đơn quá hạn
**Label:** backend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-029 · **Liên kết:** UC-07, TC-07
Scheduler mỗi phút: đơn PENDING quá `expired_at` → EXPIRED có điều kiện, hoàn kho, ghi `OrderCancelled`.
- [ ] Đơn vừa được thanh toán (update 0 dòng) → bỏ qua
- [ ] Sau khi hết hạn, user mua lại được (TC-07)
- [ ] An toàn khi nhiều instance

### FLS-033 · Đối soát Redis–MySQL + phục hồi khi mất Redis
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-027, FLS-032 · **Liên kết:** OR-11, OR-12
Job mỗi 10 phút và sau chiến dịch so `totalStock` với kho Redis + đơn PENDING/PAID; khi mất key Redis: khoá chiến dịch, nạp lại, mở lại.
- [ ] Phát hiện lệch → log + metric + cảnh báo
- [ ] Mô phỏng `FLUSHALL` → chiến dịch tự khôi phục đúng kho
- [ ] Không chạy đè lên giao dịch đang xử lý

### FLS-034 · Module thông báo email
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-028 · **Liên kết:** HFR-07, HNFR-04
Consume `OrderCreated/OrderPaid/OrderCancelled` → gửi email (Mailhog local, SES prod) theo template; retry + DLQ.
- [ ] Email lỗi không ảnh hưởng luồng mua
- [ ] Event trùng không gửi 2 email
- [ ] Template tiếng Việt + tiếng Anh

### FLS-035 · Storefront: giỏ hàng drawer + checkout
**Label:** frontend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-022, FLS-023, FLS-026 · **Liên kết:** UC-04, UC-11, OR-01
Drawer trượt phải, sửa số lượng, checkout một trang chọn/thêm địa chỉ, tạo đơn với Idempotency-Key sinh ở client.
- [ ] Idempotency-Key giữ nguyên khi retry cùng lần bấm
- [ ] Cảnh báo khi số lượng vượt `availableQuantity`
- [ ] Optimistic update giỏ hàng

### FLS-036 · Storefront: trang flash-sale
**Label:** frontend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-020, FLS-021, FLS-029 · **Liên kết:** HFR-10, OR-03, UC-05
Banner + đếm ngược theo `serverTime`, thanh kho còn lại, nút mua disable khi hết/chưa mở, poll trạng thái đơn sau 202.
- [ ] Toast theo mã lỗi `SOLD_OUT`, `LIMIT_EXCEEDED`, `CAMPAIGN_NOT_ACTIVE`, `RATE_LIMITED`
- [ ] Chặn double-click; key giữ nguyên khi retry
- [ ] Đếm ngược đúng dù đồng hồ client lệch

### FLS-037 · Storefront: thanh toán + tài khoản
**Label:** frontend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-029, FLS-030 · **Liên kết:** HFR-10, UC-06
Chuyển tới trang thanh toán giả, trang kết quả; tài khoản: lịch sử đơn, chi tiết đơn, huỷ đơn, quản lý địa chỉ.
- [ ] Hiển thị thời gian còn lại để thanh toán
- [ ] Danh sách đơn có lọc trạng thái + phân trang
- [ ] Huỷ đơn PENDING từ UI

### FLS-038 · Observability: metrics + dashboard
**Label:** infra · **Ước lượng:** 1d · **Phụ thuộc:** FLS-001, FLS-002 · **Liên kết:** HNFR-06
Prometheus + Grafana trong Compose; metric tuỳ chỉnh (purchase TPS theo kết quả, outbox backlog, Kafka consumer lag, DLQ); log JSON có traceId.
- [ ] Dashboard TPS / latency / lag được provision sẵn
- [ ] `/actuator/prometheus` có metric tuỳ chỉnh
- [ ] Ghi hướng dẫn trong README

---

## Week 4 – Frontend polish, AI & deploy

### FLS-039 · Pipeline embedding sản phẩm (pgvector)
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-011, FLS-003 · **Liên kết:** HFR-08, ADR-0005
Cấu hình Spring AI (provider đổi bằng cấu hình), `PgVectorStore`; embed khi seed và khi sản phẩm thay đổi; xoá vector khi DELETED.
- [ ] Chạy lại embed toàn bộ bằng một lệnh
- [ ] Truy vấn top-5 dùng index HNSW
- [ ] Lỗi LLM/Postgres không chặn sửa sản phẩm

### FLS-040 · API chat AI (RAG + tool calling)
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-039, FLS-029, FLS-021, FLS-019 · **Liên kết:** UC-08, SEC-13, TC-08, TC-09
`POST /ai/chat`: RAG top-5 + tools `searchProducts`, `getOrderStatus`, `getActiveFlashSales` dùng userId từ token; timeout 10 giây có fallback; hạn mức theo ngày.
- [ ] Hỏi đơn của người khác → từ chối (TC-08)
- [ ] LLM chậm > 10 giây → `fallback: true` (TC-09)
- [ ] Guest hỏi đơn → yêu cầu đăng nhập
- [ ] Vượt hạn mức → 429 `AI_QUOTA_EXCEEDED`

### FLS-041 · Widget chat AI
**Label:** frontend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-040 · **Liên kết:** HFR-08, OR-03
Nút góc dưới phải mở khung chat, giữ `conversationId`, hiện card sản phẩm trong câu trả lời.
- [ ] Hiển thị trạng thái đang trả lời và fallback
- [ ] Điều hướng được bằng bàn phím

### FLS-042 · API admin đơn hàng + khách hàng
**Label:** backend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-029 · **Liên kết:** HFR-11
`/admin/orders` (lọc trạng thái, loại, chiến dịch, thời gian), chi tiết kèm payments, complete/cancel; `/admin/customers` list + chi tiết.
- [ ] Huỷ đơn PAID → 409
- [ ] Tìm theo orderNo/email
- [ ] Chỉ ADMIN truy cập

### FLS-043 · API thống kê admin
**Label:** backend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-031, FLS-033 · **Liên kết:** HFR-09
`/admin/stats/overview`, `/admin/stats/revenue` (HOUR/DAY/MONTH), `/admin/stats/flash-sales/{campaignId}` có cờ `inSync`.
- [ ] Doanh thu chỉ tính đơn PAID/COMPLETED
- [ ] Bucket thời gian theo UTC
- [ ] Truy vấn dùng index, `EXPLAIN` không full scan bảng orders

### FLS-044 · Admin web: khung + quản lý sản phẩm và ảnh
**Label:** frontend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-009, FLS-010, FLS-026 · **Liên kết:** HFR-11, UC-10, OR-02
Sidebar kiểu Shopify admin; bảng sản phẩm tìm/lọc/phân trang; form sản phẩm kéo thả upload nhiều ảnh, sắp xếp thứ tự.
- [ ] Upload qua presigned URL, có progress
- [ ] Kéo thả đổi thứ tự gọi API reorder
- [ ] Route `/admin` chỉ cho ADMIN

### FLS-045 · Admin web: chiến dịch flash-sale
**Label:** frontend · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-015, FLS-044 · **Liên kết:** UC-09, HFR-11
Danh sách chiến dịch, form tạo/sửa chọn sản phẩm + giá sale + số lượng + limit, nút huỷ.
- [ ] Hiển thị `fieldErrors` theo từng trường
- [ ] Khoá form khi chiến dịch không còn sửa được

### FLS-046 · Admin web: đơn hàng, khách hàng, dashboard
**Label:** frontend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-042, FLS-043, FLS-044 · **Liên kết:** HFR-09, HFR-11, OR-02
Bảng đơn hàng + chi tiết, bảng khách hàng, dashboard thẻ KPI + biểu đồ doanh thu + thống kê chiến dịch.
- [ ] Bộ lọc đồng bộ URL
- [ ] Biểu đồ đổi granularity
- [ ] Hiển thị cảnh báo khi chiến dịch `inSync = false`

### FLS-047 · Polish storefront: responsive, a11y, hiệu năng
**Label:** frontend · **Ước lượng:** 1d · **Phụ thuộc:** FLS-035, FLS-036, FLS-037 · **Liên kết:** HNFR-07, HNFR-08, OR-03
Skeleton khi tải, responsive 360–1440 px, ảnh lazy-load qua CDN, alt/keyboard navigation.
- [ ] Lighthouse Performance ≥ 85, LCP < 2,5 giây ở trang chủ và trang sản phẩm
- [ ] Không vỡ layout ở 360 px
- [ ] Không có lỗi axe nghiêm trọng

### FLS-048 · E2E Playwright luồng chính
**Label:** test · **Ước lượng:** 1d · **Phụ thuộc:** FLS-036, FLS-037 · **Liên kết:** OR-10, UC-02, UC-05, UC-06
Đăng nhập → mua flash-sale → thanh toán thành công; thêm case hết hàng và thanh toán thất bại.
- [ ] Chạy trên Docker Compose trong CI
- [ ] Dữ liệu test tự tạo, không phụ thuộc thứ tự chạy
- [ ] Lưu trace/video khi fail

### FLS-049 · S3 + CloudFront cho ảnh và frontend
**Label:** infra · **Ước lượng:** 1d · **Phụ thuộc:** FLS-010, FLS-013 · **Liên kết:** OR-06, HNFR-07, SEC-08
Bucket ảnh + bucket frontend, CloudFront (OAC, HTTPS), cache policy; cấu hình storage prod trỏ S3.
- [ ] Bucket không public, chỉ CloudFront đọc được
- [ ] CORS cho presigned PUT từ domain admin
- [ ] SPA fallback về `index.html`

### FLS-050 · Deploy backend + dữ liệu lên AWS
**Label:** infra · **Ước lượng:** 1d · **Phụ thuộc:** FLS-038 · **Liên kết:** SEC-08, SEC-09, SEC-14, HNFR-03, SRS 2.3
Container backend sau load balancer (HTTPS), RDS MySQL (backup 7 ngày), Redis, Kafka, Postgres với chi phí thấp; secret qua Secrets Manager.
- [ ] Health check Actuator gắn với load balancer
- [ ] Không secret nào trong image/repo
- [ ] Ghi chi phí ước tính và cách tắt khi không dùng

### FLS-051 · CD pipeline
**Label:** infra · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-014, FLS-049, FLS-050 · **Liên kết:** HNFR-05
Merge `main` → build image, push ECR, deploy backend; build frontend, sync S3, invalidate CloudFront.
- [ ] Dùng OIDC GitHub → AWS, không lưu access key
- [ ] Deploy chỉ chạy khi CI xanh

### FLS-052 · README và tài liệu cuối
**Label:** docs · **Ước lượng:** 0.5d · **Phụ thuộc:** FLS-024, FLS-050 · **Liên kết:** OR-09, OR-13
README: kiến trúc, cách chạy, trade-off (link ADR), kết quả load test, giới hạn và lưu ý dữ liệu DummyJSON chỉ dùng học tập; cập nhật `architecture.md` và bảng lệnh trong `CLAUDE.md`.
- [ ] Người mới chạy được local từ README
- [ ] Có số liệu k6 trước/sau
- [ ] Bảng Commands trong CLAUDE.md không còn TBD
