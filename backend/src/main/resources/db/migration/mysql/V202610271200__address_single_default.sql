-- FLS-007: enforce at most one default address per user at the database level.
-- default_owner is user_id for the default address and NULL otherwise; a UNIQUE index ignores
-- NULLs, so only one row per user can have is_default = TRUE.
ALTER TABLE addresses
    ADD COLUMN default_owner BIGINT GENERATED ALWAYS AS (IF(is_default, user_id, NULL)) STORED,
    ADD UNIQUE KEY uk_addresses_one_default (default_owner);
