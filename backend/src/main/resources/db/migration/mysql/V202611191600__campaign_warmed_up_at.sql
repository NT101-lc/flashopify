-- FLS-016: remember when a campaign's stock was loaded into Redis. A warmed-up campaign is never
-- edited again, and the end-of-campaign job only reads Redis for campaigns that were warmed up.
ALTER TABLE flash_sale_campaigns
    ADD COLUMN warmed_up_at DATETIME NULL AFTER status,
    ADD KEY idx_campaigns_status_end (status, end_time);
