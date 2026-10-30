-- Runs only when the postgres-data volume is empty (first start or after `down -v`).
-- On an existing volume, Flyway V1__ai_init.sql creates the extension when the backend starts.
CREATE EXTENSION IF NOT EXISTS vector;
