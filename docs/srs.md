# Software Requirements Specification

Oct 27, 2026 · @Nam Anh



Bản SRS theo cấu trúc 7 phần: Giới thiệu, Yêu cầu tổng thể, Bảo mật, Use Case, Yêu cầu khác, Tích hợp, Phụ lục.



\## 1. Giới thiệu (Introduction)



\### 1.1 Mục tiêu



Tài liệu đặc tả yêu cầu cho nền tảng thương mại điện tử có flash-sale: bán số lượng hàng giới hạn, giá giảm sâu, trong khung giờ ngắn với lưu lượng đột biến. SRS này là cơ sở để thiết kế, code, viết test và nghiệm thu từng giai đoạn của project.



\### 1.2 Phạm vi dự án



Hệ thống gồm backend REST API và frontend web React + TypeScript với hai phần: cửa hàng cho khách (storefront) và trang quản trị (admin). Chức năng: quản lý người dùng, sản phẩm có ảnh, tồn kho, giỏ hàng, đơn hàng; chiến dịch flash-sale chống oversell; thanh toán giả lập; thông báo; AI assistant hỏi đáp sản phẩm và tra cứu đơn. Giao diện tham khảo bố cục và trải nghiệm của Shopify; sản phẩm và ảnh được seed từ DummyJSON.



Ngoài phạm vi: thanh toán thật, vận chuyển thật, mobile app native.



\### 1.3 Đối tượng sử dụng tài liệu



| Đối tượng | Mục đích đọc |

| --- | --- |

| Developer | Hiểu yêu cầu để thiết kế và code |

| Tester | Xây dựng test case từ use case và tiêu chí chấp nhận |

| Reviewer / người phỏng vấn | Đánh giá phạm vi và quyết định thiết kế của project |



\### 1.4 Tài liệu tham chiếu



\- IEEE 830 / ISO/IEC/IEEE 29148 (chuẩn đặc tả yêu cầu)

\- \[Hướng dẫn viết tài liệu SRS – ITG Technology](https://itgtechnology.vn/tai-lieu-srs/)

\- Tài liệu chính thức Spring Boot, Spring Security, Spring AI, MyBatis, Redis, Kafka



\### 1.5 Định nghĩa thuật ngữ



| Thuật ngữ | Định nghĩa |

| --- | --- |

| Flash-sale | Đợt bán giới hạn số lượng và thời gian |

| Oversell | Bán vượt số lượng tồn kho thực tế |

| Idempotency | Gửi lặp cùng một request chỉ tạo một kết quả |

| Outbox pattern | Ghi event vào bảng outbox cùng transaction với dữ liệu, sau đó mới publish lên queue |

| Saga | Chuỗi bước giao dịch có bước bù trừ khi một bước lỗi |

| RAG | LLM trả lời dựa trên dữ liệu truy xuất từ vector DB |

| DLQ | Dead-letter queue, nơi chứa message xử lý lỗi nhiều lần |

| UC / FR / NFR | Use case / Functional requirement / Non-functional requirement |



\## 2. Yêu cầu mức tổng thể (High Level Requirement)



\### 2.1 Yêu cầu chức năng cấp cao



| ID | Nhóm chức năng | Mục đích và phạm vi | Ưu tiên |

| --- | --- | --- | --- |

| HFR-01 | Xác thực \& người dùng | Đăng ký, đăng nhập JWT, refresh/logout, phân quyền CUSTOMER/ADMIN, địa chỉ giao hàng | Cao |

| HFR-02 | Sản phẩm \& danh mục | Admin quản lý sản phẩm kèm nhiều ảnh; khách xem, lọc, tìm kiếm, sắp xếp; chi tiết có cache | Cao |

| HFR-03 | Tồn kho | Trừ kho thường bằng optimistic lock; kho flash-sale trên Redis; hoàn kho; đối soát | Cao |

| HFR-04 | Flash-sale | Tạo chiến dịch, giới hạn mua mỗi user, chặn ngoài giờ/hết hàng, rate limit, tạo đơn bất đồng bộ | Cao |

| HFR-05 | Giỏ hàng \& đơn hàng | Giỏ hàng lưu phía server, tạo đơn idempotent, state machine, tự hết hạn sau 15 phút, lịch sử đơn | Cao |

| HFR-06 | Thanh toán giả lập | Tạo phiên thanh toán, webhook kết quả, cấu hình lỗi/độ trễ để test | Cao |

| HFR-07 | Thông báo | Gửi email theo event đơn hàng, retry, DLQ | Trung bình |

| HFR-08 | AI assistant | Hỏi đáp sản phẩm bằng RAG, tra cứu đơn của chính user qua tool calling; hiển thị dạng chat widget trên storefront | Trung bình |

| HFR-09 | Quản trị \& thống kê | Theo dõi tồn kho và đơn theo chiến dịch, báo cáo doanh thu | Thấp |

| HFR-10 | Storefront web | Trang chủ, danh mục, chi tiết sản phẩm, giỏ hàng, checkout, tài khoản, lịch sử đơn, trang flash-sale có đếm ngược | Cao |

| HFR-11 | Admin web | Quản lý sản phẩm và ảnh, chiến dịch flash-sale, đơn hàng, khách hàng, dashboard thống kê | Cao |

| HFR-12 | Seed dữ liệu | Import sản phẩm, danh mục và ảnh từ DummyJSON; sinh khách hàng và chiến dịch mẫu | Cao |



\### 2.2 Yêu cầu phi chức năng cấp cao



| ID | Thuộc tính | Yêu cầu đo được |

| --- | --- | --- |

| HNFR-01 | Hiệu năng | Endpoint mua flash-sale ≥ 2.000 request/giây, p99 < 200 ms; xem sản phẩm p95 < 50 ms khi cache hit |

| HNFR-02 | Tính đúng | 0 đơn oversell khi 10.000 request tranh 100 sản phẩm |

| HNFR-03 | Khả năng mở rộng | Scale ngang app server không cần sửa code (stateless, session ở JWT/Redis) |

| HNFR-04 | Tính khả dụng | Lỗi LLM hoặc notification không ảnh hưởng luồng mua hàng |

| HNFR-05 | Khả năng bảo trì | Modular monolith, coverage service layer ≥ 70%, CI chạy mỗi PR |

| HNFR-06 | Quan sát được | Metrics, log có traceId, dashboard TPS / latency / lag queue |

| HNFR-07 | Hiệu năng frontend | LCP < 2,5 giây ở trang chủ và trang sản phẩm; Lighthouse Performance ≥ 85; ảnh lazy-load qua CDN |

| HNFR-08 | Responsive | Dùng tốt từ 360 px (mobile) đến 1440 px (desktop) |



\### 2.3 Ràng buộc



\- Backend: Java 17, Spring Boot 3, MyBatis, Lombok; MySQL 8, Redis 7, Kafka

\- Frontend: React 18 + TypeScript (strict mode), Vite, React Router, TanStack Query, Tailwind CSS + shadcn/ui

\- Ảnh lưu ở object storage: MinIO khi chạy local, S3 + CloudFront khi deploy

\- Một người phát triển, khoảng 4 tuần

\- Chạy local bằng Docker Compose; deploy AWS với chi phí thấp (free tier hoặc tắt khi không dùng)



\### 2.4 Giả định



\- Cổng thanh toán là giả lập, trả SUCCESS / FAILED / timeout theo cấu hình

\- LLM gọi qua API bên ngoài và có thể không ổn định; có thể thay bằng Ollama chạy local

\- Tải được tạo bằng công cụ load test, không có người dùng thật

\- Người dùng truy cập qua trình duyệt desktop và mobile; Swagger UI chỉ dùng cho dev/test

\- Dữ liệu và ảnh từ DummyJSON chỉ dùng cho mục đích học tập, không thương mại



\## 3. Yêu cầu bảo mật (Security Requirement)



\### 3.1 Quản lý truy cập



| ID | Yêu cầu |

| --- | --- |

| SEC-01 | Xác thực bằng email + mật khẩu; mật khẩu tối thiểu 8 ký tự, hash BCrypt (cost 10) |

| SEC-02 | Access token JWT hết hạn sau 15 phút; refresh token 7 ngày, lưu trong Redis để thu hồi được |

| SEC-03 | Logout thu hồi refresh token; đổi mật khẩu thu hồi toàn bộ refresh token của user |

| SEC-04 | Phân quyền theo role: Guest chỉ đọc; Customer mua hàng và xem dữ liệu của mình; Admin quản trị |

| SEC-05 | Khoá đăng nhập 15 phút sau 5 lần sai liên tiếp |

| SEC-06 | Rate limit theo user và IP cho endpoint đăng nhập, mua flash-sale và AI chat; vượt ngưỡng trả 429 |

| SEC-07 | Mọi truy vấn đơn hàng, thanh toán, AI tool đều lọc theo userId lấy từ token, không tin userId trong body |



\### 3.2 Bảo vệ dữ liệu



| ID | Yêu cầu |

| --- | --- |

| SEC-08 | HTTPS cho mọi kết nối khi deploy (TLS kết thúc tại load balancer) |

| SEC-09 | Secret (DB password, JWT key, LLM API key) để trong biến môi trường / AWS Secrets Manager, không commit vào repo |

| SEC-10 | Dùng tham số MyBatis `#{}`, không nối chuỗi SQL; validate input bằng Bean Validation |

| SEC-11 | Log không chứa mật khẩu, token, thông tin thanh toán |

| SEC-12 | Webhook thanh toán xác thực bằng chữ ký HMAC, chặn callback giả |

| SEC-13 | AI assistant: system prompt cấm tiết lộ dữ liệu người khác; tool chỉ nhận tham số đã kiểm tra quyền; không đưa dữ liệu nhạy cảm vào prompt |

| SEC-14 | Backup MySQL hằng ngày (RDS automated backup), giữ 7 ngày |



\## 4. Đặc tả Use Case (Use Case Specification)



\### 4.1 Danh sách Use Case



| ID | Tên Use Case | Actor | Liên kết |

| --- | --- | --- | --- |

| UC-01 | Đăng ký tài khoản | Guest | HFR-01 |

| UC-02 | Đăng nhập | Guest | HFR-01 |

| UC-03 | Xem và tìm kiếm sản phẩm | Guest, Customer | HFR-02, HFR-10 |

| UC-04 | Đặt đơn thường | Customer | HFR-05 |

| UC-05 | Mua sản phẩm flash-sale | Customer | HFR-04 |

| UC-06 | Thanh toán đơn hàng | Customer, Payment Gateway | HFR-06 |

| UC-07 | Tự huỷ đơn quá hạn | Hệ thống (scheduler) | HFR-05 |

| UC-08 | Hỏi AI assistant | Guest, Customer | HFR-08 |

| UC-09 | Tạo chiến dịch flash-sale | Admin | HFR-04, HFR-11 |

| UC-10 | Quản lý sản phẩm và ảnh | Admin | HFR-02, HFR-11 |

| UC-11 | Quản lý giỏ hàng | Customer | HFR-05, HFR-10 |

| UC-12 | Seed dữ liệu sản phẩm | Admin, Hệ thống | HFR-12 |



Dưới đây đặc tả chi tiết các use case cốt lõi; các UC còn lại theo cùng mẫu.



\### 4.2 UC-02: Đăng nhập



| Mục | Nội dung |

| --- | --- |

| Mô tả | Người dùng đăng nhập để nhận access token và refresh token |

| Actor | Guest |

| Điều kiện tiên quyết | Tài khoản đã đăng ký, chưa bị khoá |

| Luồng chính | 1. Gửi email + mật khẩu tới POST /api/v1/auth/login. 2. Hệ thống kiểm tra rate limit. 3. So khớp mật khẩu BCrypt. 4. Sinh access token và refresh token, lưu refresh token vào Redis. 5. Trả 200 kèm 2 token |

| Luồng thay thế | 3a. Sai mật khẩu → tăng bộ đếm, trả 401. 3b. Sai 5 lần → khoá 15 phút, trả 423. 2a. Vượt rate limit → trả 429 |

| Kết quả mong đợi | Người dùng có token hợp lệ để gọi API cần xác thực |



\### 4.3 UC-05: Mua sản phẩm flash-sale



| Mục | Nội dung |

| --- | --- |

| Mô tả | Khách hàng mua sản phẩm trong chiến dịch flash-sale đang mở |

| Actor | Customer |

| Điều kiện tiên quyết | Đã đăng nhập; chiến dịch đang trong khung giờ; tồn kho đã warm-up vào Redis |

| Luồng chính | 1. Gửi POST /api/v1/flash-sales/{itemId}/purchase kèm header Idempotency-Key. 2. Kiểm tra JWT và rate limit. 3. Kiểm tra Idempotency-Key chưa từng xử lý. 4. Lua script kiểm tra giờ mở bán, giới hạn mua của user, tồn kho > 0, rồi trừ kho và tăng số đã mua (atomic). 5. Sinh orderNo, gửi message tạo đơn lên Kafka. 6. Trả 202 kèm orderNo, trạng thái PROCESSING. 7. Consumer ghi đơn PENDING vào MySQL và event OrderCreated vào outbox |

| Luồng thay thế | 3a. Key đã xử lý → trả lại kết quả cũ. 4a. Hết hàng → 409 SOLD\\\_OUT. 4b. Vượt giới hạn → 409 LIMIT\\\_EXCEEDED. 4c. Ngoài khung giờ → 400 CAMPAIGN\\\_NOT\\\_ACTIVE. 7a. Consumer lỗi 3 lần → message vào DLQ, job đối soát hoàn kho |

| Kết quả mong đợi | Kho Redis giảm đúng 1 đơn vị mỗi lượt mua hợp lệ; đơn PENDING được tạo; không bao giờ oversell |



\### 4.4 UC-06: Thanh toán đơn hàng



| Mục | Nội dung |

| --- | --- |

| Mô tả | Khách hàng thanh toán đơn PENDING qua cổng thanh toán giả lập |

| Actor | Customer, Payment Gateway (mock) |

| Điều kiện tiên quyết | Đơn ở trạng thái PENDING, chưa quá 15 phút |

| Luồng chính | 1. Gửi POST /api/v1/payments/{orderNo}. 2. Hệ thống tạo bản ghi payment, trả URL thanh toán giả. 3. Gateway gọi webhook /payments/callback với chữ ký HMAC. 4. Xác thực chữ ký, kiểm tra provider\\\_txn\\\_id chưa xử lý. 5. Cập nhật đơn sang PAID, trừ kho MySQL, ghi event OrderPaid. 6. Gửi email xác nhận |

| Luồng thay thế | 4a. Chữ ký sai → 401, bỏ qua. 4b. Callback trùng → trả 200, không xử lý lại. 5a. Kết quả FAILED → đơn CANCELLED, hoàn kho Redis và giảm số đã mua |

| Kết quả mong đợi | Đơn PAID, tồn kho MySQL khớp; hoặc đơn huỷ và kho được hoàn |



\### 4.5 UC-07: Tự huỷ đơn quá hạn



| Mục | Nội dung |

| --- | --- |

| Mô tả | Hệ thống huỷ đơn chưa thanh toán sau 15 phút và hoàn kho |

| Actor | Hệ thống |

| Điều kiện tiên quyết | Đơn PENDING có expired\\\_at ≤ thời điểm hiện tại |

| Luồng chính | 1. Scheduler chạy mỗi phút (hoặc nhận delayed message). 2. Lấy các đơn quá hạn. 3. Cập nhật EXPIRED có điều kiện status = PENDING. 4. Hoàn kho Redis, giảm số đã mua. 5. Ghi event OrderCancelled |

| Luồng thay thế | 3a. Đơn vừa được thanh toán (update 0 dòng) → bỏ qua |

| Kết quả mong đợi | Không có đơn PENDING quá hạn tồn tại; kho được trả lại cho người mua khác |



\### 4.6 UC-08: Hỏi AI assistant



| Mục | Nội dung |

| --- | --- |

| Mô tả | Người dùng hỏi về sản phẩm, flash-sale hoặc đơn hàng bằng ngôn ngữ tự nhiên |

| Actor | Guest, Customer |

| Điều kiện tiên quyết | Chưa vượt hạn mức câu hỏi trong ngày; hỏi về đơn hàng cần đăng nhập |

| Luồng chính | 1. Gửi câu hỏi tới POST /api/v1/ai/chat. 2. Embed câu hỏi, truy xuất top-5 mô tả sản phẩm từ pgvector. 3. Gửi ngữ cảnh + câu hỏi tới LLM kèm danh sách tool. 4. LLM gọi tool (searchProducts, getOrderStatus, getActiveFlashSales); hệ thống thực thi với userId từ token. 5. Trả câu trả lời |

| Luồng thay thế | 3a. LLM quá 10 giây hoặc lỗi → trả thông báo fallback. 4a. Guest hỏi về đơn → yêu cầu đăng nhập. 1a. Vượt hạn mức → 429 |

| Kết quả mong đợi | Câu trả lời đúng với dữ liệu thật, không lộ dữ liệu của người khác |



\### 4.7 UC-09: Tạo chiến dịch flash-sale



| Mục | Nội dung |

| --- | --- |

| Mô tả | Admin tạo chiến dịch và sản phẩm tham gia |

| Actor | Admin |

| Điều kiện tiên quyết | Đăng nhập với role ADMIN; sản phẩm tồn tại và đủ tồn kho |

| Luồng chính | 1. Gửi tên, giờ bắt đầu/kết thúc, danh sách sản phẩm (giá sale, số lượng, giới hạn mỗi user). 2. Validate: giờ bắt đầu ở tương lai, số lượng ≤ tồn kho. 3. Lưu chiến dịch trạng thái SCHEDULED, giữ tồn kho. 4. Trước giờ mở bán 5 phút, job warm-up nạp tồn kho vào Redis |

| Luồng thay thế | 2a. Dữ liệu không hợp lệ → 400 kèm chi tiết lỗi từng trường |

| Kết quả mong đợi | Chiến dịch sẵn sàng, kho Redis được nạp đúng số lượng trước giờ mở bán |



\### 4.8 UC-12: Seed dữ liệu sản phẩm



| Mục | Nội dung |

| --- | --- |

| Mô tả | Nhập sản phẩm, danh mục và ảnh mẫu từ DummyJSON vào hệ thống |

| Actor | Admin (chạy lệnh seed) hoặc hệ thống khi khởi động với profile seed |

| Điều kiện tiên quyết | DB đã migrate; object storage sẵn sàng; có kết nối internet |

| Luồng chính | 1. Gọi GET /products/categories của DummyJSON, tạo danh mục. 2. Gọi GET /products, phân trang bằng limit/skip đến hết. 3. Với mỗi sản phẩm: tải thumbnail và images, upload lên object storage. 4. Ghi product, product\\\_images, inventory (stock lấy từ dữ liệu nguồn) trong một transaction. 5. Sinh embedding cho AI. 6. Log tổng số đã import |

| Luồng thay thế | 3a. Tải ảnh lỗi → retry 3 lần, sau đó dùng ảnh placeholder và ghi cảnh báo. 4a. Sản phẩm đã có (theo source\\\_id) → bỏ qua, chạy lại không tạo trùng |

| Kết quả mong đợi | Storefront hiển thị đủ sản phẩm có ảnh; sau seed hệ thống không gọi DummyJSON nữa |



\## 5. Những yêu cầu khác (Other Requirement)



| ID | Nhóm | Yêu cầu |

| --- | --- | --- |

| OR-01 | Giao diện storefront | Tham khảo bố cục Shopify (theme Dawn): header có logo, tìm kiếm, icon giỏ hàng; trang chủ có banner flash-sale với đồng hồ đếm ngược và lưới sản phẩm nổi bật; trang danh mục dạng lưới ảnh, bộ lọc bên trái (giá, brand, rating), sắp xếp; trang sản phẩm có gallery ảnh (ảnh lớn + thumbnail), giá gốc gạch ngang và giá sale, chọn số lượng; giỏ hàng dạng drawer trượt từ phải; checkout một trang |

| OR-02 | Giao diện admin | Tham khảo Shopify admin: sidebar (Tổng quan, Đơn hàng, Sản phẩm, Flash-sale, Khách hàng); bảng danh sách có tìm kiếm, lọc, phân trang, thao tác hàng loạt; form sản phẩm có kéo thả upload nhiều ảnh và sắp xếp thứ tự ảnh; dashboard thẻ KPI + biểu đồ doanh thu |

| OR-03 | Giao diện chung | Chỉ tham khảo bố cục và trải nghiệm, dùng logo, tên và màu sắc riêng, không dùng asset của Shopify; skeleton khi tải; toast lỗi theo mã lỗi (SOLD\\\_OUT, LIMIT\\\_EXCEEDED); nút mua disable khi hết hàng hoặc chưa mở bán; AI chat widget ở góc dưới bên phải; ảnh có alt, điều hướng được bằng bàn phím |

| OR-04 | Dữ liệu | MySQL là nguồn sự thật cho đơn, thanh toán, sản phẩm; Redis chỉ giữ dữ liệu tạm (kho flash-sale, cache, rate limit); schema quản lý bằng Flyway; tiền lưu DECIMAL(15,2), thời gian lưu UTC |

| OR-05 | Dữ liệu | Không xoá cứng đơn hàng và thanh toán; sản phẩm xoá mềm (status = DELETED) |

| OR-06 | Ảnh sản phẩm | Lưu ở object storage, DB chỉ lưu URL và thứ tự (bảng product\\\_images); mỗi sản phẩm 1 thumbnail + tối đa 8 ảnh; JPG/PNG/WebP, tối đa 5 MB mỗi ảnh; upload qua presigned URL |

| OR-07 | Seed dữ liệu | Import một lần từ DummyJSON: khoảng 194 sản phẩm (tên, mô tả, giá, brand, danh mục, rating, stock, thumbnail, images) và danh mục; ảnh tải về object storage, không hotlink; giá giữ USD hoặc quy đổi VND theo tỉ giá cố định trong cấu hình; sinh thêm 1.000 customer và 1 chiến dịch flash-sale mẫu |

| OR-08 | Khả năng sử dụng | Response lỗi thống nhất `{ code, message, traceId, timestamp }`; AI trả lời tiếng Việt và tiếng Anh |

| OR-09 | Khả năng bảo trì | Backend mỗi domain một module, chỉ gọi nhau qua interface hoặc event; frontend chia theo feature (catalog, cart, checkout, flash-sale, admin); TypeScript type sinh tự động từ OpenAPI; README ghi quyết định thiết kế và trade-off |

| OR-10 | Kiểm thử | Backend: unit test service ≥ 70% coverage, integration test với Testcontainers. Frontend: Vitest + React Testing Library cho component, Playwright e2e cho luồng đăng nhập → mua flash-sale → thanh toán. CI GitHub Actions chạy lint (ESLint, Prettier) + test mỗi PR |

| OR-11 | Phục hồi lỗi | Consumer Kafka retry 3 lần có backoff rồi vào DLQ; job đối soát kho Redis–MySQL mỗi 10 phút và sau mỗi chiến dịch |

| OR-12 | Phục hồi lỗi | Redis mất dữ liệu → tạm khoá chiến dịch, nạp lại kho từ MySQL (tổng kho trừ đơn PENDING/PAID) rồi mở lại; health check Spring Actuator để load balancer loại instance lỗi |

| OR-13 | Pháp lý | Không lưu thông tin thẻ; chỉ thu thập dữ liệu cá nhân tối thiểu; cho phép user yêu cầu xoá tài khoản (tham khảo Nghị định 13/2023/NĐ-CP); dữ liệu DummyJSON chỉ dùng học tập |



\## 6. Yêu cầu tích hợp (Integration)



\### 6.1 Giao diện phần cứng



Không có yêu cầu tích hợp thiết bị phần cứng.



\### 6.2 Giao diện tích hợp



| Hệ thống | Hướng | Giao thức / định dạng | Mục đích |

| --- | --- | --- | --- |

| Frontend React (storefront + admin) | Vào | REST JSON qua HTTPS, CORS giới hạn domain frontend | Gọi toàn bộ API nghiệp vụ |

| Swagger UI, k6 | Vào | REST JSON | Dev, test, load test |

| Payment Gateway (mock) | Vào + ra | REST JSON, webhook ký HMAC-SHA256 | Tạo phiên thanh toán, nhận kết quả |

| LLM provider (OpenAI / Gemini / Ollama) | Ra | REST JSON qua Spring AI | Embedding và chat completion |

| \[DummyJSON](https://dummyjson.com/docs/products) | Ra, chạy một lần khi seed | REST JSON | Lấy sản phẩm, danh mục, URL ảnh |

| Object storage (MinIO / S3 + CloudFront) | Ra | S3 API, presigned URL | Lưu và phân phối ảnh sản phẩm |

| Email (Mailhog local, AWS SES) | Ra | SMTP | Gửi thông báo đơn hàng |

| Kafka | Nội bộ | Kafka protocol, message JSON | Event đơn hàng, thanh toán |

| Prometheus | Vào | HTTP /actuator/prometheus | Thu metrics |



\### 6.3 Khả năng tương thích



\- API versioning qua đường dẫn `/api/v1`; thay đổi phá vỡ tương thích phải lên `/api/v2`

\- Frontend hỗ trợ 2 phiên bản mới nhất của Chrome, Edge, Firefox, Safari; iOS Safari và Chrome Android

\- Chạy được trên mọi môi trường có Docker (Linux, macOS, Windows/WSL2)

\- Đổi LLM provider hoặc object storage chỉ bằng cấu hình, không sửa code



\### 6.4 Chuẩn giao tiếp



\- RESTful, mã HTTP chuẩn (200, 201, 202, 400, 401, 403, 404, 409, 429, 500)

\- Tài liệu API theo OpenAPI 3 (springdoc)

\- JSON camelCase, thời gian ISO-8601 UTC

\- Header bắt buộc: `Authorization: Bearer <token>` cho API cần xác thực, `Idempotency-Key` cho API tạo đơn/mua, `X-Trace-Id` được trả về trong mọi response

\- Event Kafka có schema cố định: eventId, eventType, aggregateId, occurredAt, payload



\## 7. Phụ lục (Appendices)



\### 7.1 Sơ đồ luồng mua flash-sale



\&#91;embedded content: luồng mua flash-sale · UC-05, UC-06, UC-07\\]



Ba bước kiểm tra đầu chạy hoàn toàn trên Redis; chỉ request đã giữ được hàng mới đi tiếp vào Kafka và MySQL.



\### 7.2 Dữ liệu mẫu



Request mua flash-sale:



```json

POST /api/v1/flash-sales/101/purchase

Authorization: Bearer eyJhbGciOi...

Idempotency-Key: 7f3c2a10-5b1e-4c8d-9a2f-1e6b0d4c9e77



{ "quantity": 1, "addressId": 12 }

```



Response thành công (202) và hết hàng (409):



```json

{ "orderNo": "FS20261127000123", "status": "PROCESSING" }



{ "code": "SOLD\_OUT", "message": "Sản phẩm đã hết", "traceId": "a1b2c3d4", "timestamp": "2026-11-27T12:00:01Z" }

```



Bộ dữ liệu seed: khoảng 194 sản phẩm và danh mục từ \[DummyJSON](https://dummyjson.com/docs/products), mỗi sản phẩm có thumbnail và gallery ảnh; 1 admin; 1.000 customer (sinh bằng script); 1 chiến dịch 100 sản phẩm, giới hạn 1 sản phẩm mỗi user.



\### 7.3 Kịch bản kiểm thử ban đầu



| ID | Use Case | Kịch bản | Kết quả mong đợi |

| --- | --- | --- | --- |

| TC-01 | UC-05 | 10.000 request tranh 100 sản phẩm trong 10 giây | Đúng 100 đơn, kho Redis = 0, không oversell |

| TC-02 | UC-05 | 1 user gửi 5 request cùng lúc với limit 1 | Chỉ 1 request thành công, 4 request nhận 409 LIMIT\\\_EXCEEDED |

| TC-03 | UC-05 | Gửi lại cùng Idempotency-Key 3 lần | Cả 3 lần trả cùng orderNo, chỉ 1 đơn trong DB |

| TC-04 | UC-05 | Gọi trước giờ mở bán | 400 CAMPAIGN\\\_NOT\\\_ACTIVE |

| TC-05 | UC-06 | Callback SUCCESS gửi trùng 2 lần | Đơn PAID một lần, kho MySQL trừ một lần |

| TC-06 | UC-06 | Callback FAILED | Đơn CANCELLED, kho Redis tăng lại 1 |

| TC-07 | UC-07 | Không thanh toán sau 15 phút | Đơn EXPIRED, kho được hoàn, user mua lại được |

| TC-08 | UC-08 | Customer A hỏi trạng thái đơn của customer B | AI từ chối, không trả dữ liệu của B |

| TC-09 | UC-08 | LLM không phản hồi quá 10 giây | Trả thông báo fallback, không treo |

| TC-10 | UC-02 | Sai mật khẩu 5 lần | Tài khoản khoá 15 phút, trả 423 |



\### 7.4 Tài liệu tham khảo



\- \[Tài liệu SRS là gì? Hướng dẫn viết tài liệu SRS chuyên nghiệp – ITG Technology](https://itgtechnology.vn/tai-lieu-srs/)

\- \[DummyJSON – Products docs](https://dummyjson.com/docs/products)

\- Shopify theme Dawn và Shopify admin (tham khảo bố cục, UX)

\- ISO/IEC/IEEE 29148: Requirements engineering

\- Chris Richardson, Microservices Patterns (outbox, saga)

\- Tài liệu chính thức Spring Boot, Spring AI, Redis, Apache Kafka, React, TypeScript



