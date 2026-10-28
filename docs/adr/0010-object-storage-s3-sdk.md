# ADR-0010: Object storage qua AWS SDK v2 cho cả MinIO và S3, upload bằng presigned PUT

- **Status:** Accepted
- **Date:** 2026-10-28

## Context
Ảnh sản phẩm nằm ở object storage: MinIO khi chạy local, S3 + CloudFront khi deploy (SRS). Admin cần upload ảnh tới 5 MB mà không đẩy file qua backend, và backend không được tin client về loại/kích thước file. Yêu cầu FLS-010: đổi MinIO sang S3 chỉ bằng cấu hình.

## Decision
- Dùng **AWS SDK for Java v2** (`software.amazon.awssdk:s3`, client đồng bộ, không dùng netty) cho **cả** MinIO và S3. MinIO nói giao thức S3 nên chỉ cần `endpoint` + `path-style-access`. Không có lớp "provider" và không có `if (minio)`: giao diện `ObjectStorage` (module `common.storage`) chỉ có một cài đặt `S3ObjectStorage`.
- Chuyển môi trường chỉ bằng `app.storage.*` (biến `STORAGE_*`): đặt `endpoint` cho MinIO, để trống cho S3 (khi đó dùng default credentials chain nếu không có access key). `presign-endpoint` cho trường hợp trình duyệt truy cập storage bằng host khác backend; `public-base-url` là tiền tố URL lưu vào DB (MinIO bucket URL hoặc CloudFront).
- Luồng 2 bước:
  1. `POST /admin/products/{id}/images/upload-url`: backend kiểm tra loại (JPG/PNG/WebP), kích thước (≤ 5 MB), giới hạn 8 ảnh, rồi tạo key **do server sinh** `products/{id}/tmp/{uuid}.{ext}` (không dùng `fileName` của client) và trả presigned PUT. `Content-Type` và `Content-Length` nằm trong chữ ký nên storage từ chối upload khác loại/kích thước đã khai báo.
  2. Client PUT trực tiếp lên storage, rồi `POST /admin/products/{id}/images` với `objectKey`.
- Khi đăng ký ảnh, backend khoá hàng `products` (`FOR UPDATE`, để giới hạn 8 ảnh không bị vượt khi đồng thời), chỉ chấp nhận key `tmp` của đúng sản phẩm đó, `HEAD` object và **kiểm tra lại** kích thước và Content-Type (presigned URL chỉ là gợi ý). Hợp lệ thì **copy** sang key cuối `products/{id}/{uuid}.{ext}`, lưu URL công khai vào `product_images`; object `tmp` bị xoá sau commit. Vi phạm thì xoá object và trả 400.
- Xoá ảnh: object bị xoá **sau commit**; lỗi storage chỉ ghi log WARN, không làm hỏng request.

## Alternatives considered
- **MinIO SDK riêng cho local, AWS SDK cho prod**: hai code path, đổi môi trường là đổi code.
- **Upload multipart qua backend**: đơn giản nhưng tốn băng thông/thread của backend, cần giới hạn kích thước ở nhiều tầng.
- **Dùng key do client gửi**: cho phép ghi đè/đọc object của sản phẩm khác (path traversal logic); bị loại.
- **Không copy tmp sang key cuối**: client còn giữ presigned URL tới đúng key đó và có thể ghi đè nội dung sau khi đã đăng ký.

## Consequences
- Cần CORS trên bucket cho `PUT` từ origin của admin web (làm cùng FLS-044/FLS-049).
- Object `tmp` bị bỏ dở (upload xong nhưng không đăng ký) cần lifecycle rule hết hạn `products/*/tmp/` (FLS-049).
- Copy diễn ra trong transaction giữ khoá sản phẩm; nếu transaction rollback thì object cuối được dọn bằng best-effort, còn lỗi dọn dẹp chỉ để lại object mồ côi vô hại.
- Có thêm phụ thuộc `software.amazon.awssdk:s3` (kéo theo HTTP client của SDK).
