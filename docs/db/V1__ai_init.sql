-- Flashopify - V1 AI schema (PostgreSQL 16 + pgvector)
-- Location: backend/src/main/resources/db/migration/postgres/V1__ai_init.sql

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE product_embeddings (
    product_id  BIGINT       PRIMARY KEY, -- tham chieu logic toi MySQL products.id, khong co FK
    content     TEXT         NOT NULL,
    embedding   vector(1536) NOT NULL,   -- doi so chieu theo embedding model dang dung
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- HNSW index cho cosine similarity
CREATE INDEX idx_product_embeddings_hnsw
    ON product_embeddings
    USING hnsw (embedding vector_cosine_ops);
