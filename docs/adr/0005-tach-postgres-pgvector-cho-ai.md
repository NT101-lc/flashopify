# ADR-0005: Tách PostgreSQL + pgvector riêng cho module AI

- **Status:** Accepted
- **Date:** 2026-10-27

## Context
AI assistant (UC-08) dùng RAG: embed câu hỏi rồi tìm top-5 mô tả sản phẩm gần nhất, sau đó gửi cho LLM. Cần lưu khoảng vài trăm đến vài nghìn vector (1536 chiều) và tìm theo cosine similarity. Dữ liệu nghiệp vụ nằm ở MySQL 8, vốn không có kiểu vector hay index ANN. Lỗi ở AI không được ảnh hưởng luồng mua (HNFR-04). Spring AI có sẵn `PgVectorStore`.

## Decision
- Chạy thêm **PostgreSQL 16 + pgvector** chỉ cho module `ai`, schema riêng do Flyway quản lý (`db/migration/postgres`).
- Bảng `product_embeddings(product_id, content, embedding vector(1536), updated_at)` với **HNSW index** `vector_cosine_ops`. `product_id` tham chiếu logic sang MySQL, **không có FK**.
- Embedding được sinh khi seed (ADR-0006) và cập nhật khi module ai nhận event thay đổi sản phẩm. Sản phẩm DELETED thì xoá vector.
- Module `ai` lấy dữ liệu realtime (giá, tồn kho, trạng thái đơn) qua service interface của các module khác, không đọc vector để lấy giá.
- Kết nối Postgres dùng datasource riêng. Nếu Postgres hoặc LLM lỗi thì chat trả fallback, các API khác không bị ảnh hưởng.

## Alternatives considered
- **Lưu vector trong MySQL** (JSON/BLOB rồi tính cosine ở app): phải quét toàn bảng, không có index ANN, chậm khi dữ liệu tăng. MySQL HeatWave có vector nhưng không có bản chạy local miễn phí phù hợp.
- **Vector DB chuyên dụng** (Qdrant, Milvus, Pinecone): mạnh nhưng thêm một hệ thống mới phải học và vận hành; Pinecone còn là SaaS trả phí. pgvector là SQL quen thuộc và đủ tốt ở quy mô này.
- **Chuyển toàn bộ sang PostgreSQL**: hợp lý về lâu dài, nhưng SRS đã chốt MySQL cho dữ liệu nghiệp vụ và mục tiêu học MySQL.
- **Elasticsearch/OpenSearch kNN**: nặng RAM, quá mức cần thiết.

## Consequences
- Có thêm một DB phải chạy trong Docker Compose, backup và giám sát. Trên AWS sẽ tốn thêm chi phí (có thể dùng RDS free tier hoặc tắt khi không demo).
- Dữ liệu giữa hai DB là eventual consistency: vector có thể cũ vài giây sau khi sửa sản phẩm, chấp nhận được cho RAG.
- Đổi embedding model mà khác số chiều thì phải migrate cột và re-embed toàn bộ.
- AI bị cô lập rõ ràng, nên dễ tắt hoặc thay thế mà không đụng tới luồng mua hàng.
