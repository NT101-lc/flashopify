package com.flashopify.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts flash-sale rows directly (the campaign API is not built yet). Names are random so tests
 * never depend on empty tables.
 */
public final class FlashSaleFixtures {

  private final JdbcTemplate jdbc;

  public FlashSaleFixtures(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A user with role ADMIN, needed as created_by of a campaign. */
  public long adminUser() {
    String email = "adm-" + CatalogFixtures.rnd() + "@example.com";
    jdbc.update(
        "INSERT INTO users (email, password_hash, full_name, role) VALUES (?, 'x', 'Admin', 'ADMIN')",
        email);
    return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
  }

  public long campaign(long createdBy, String status) {
    String name = "camp-" + CatalogFixtures.rnd();
    jdbc.update(
        "INSERT INTO flash_sale_campaigns (name, start_time, end_time, status, created_by)"
            + " VALUES (?, NOW(), NOW() + INTERVAL 1 DAY, ?, ?)",
        name,
        status,
        createdBy);
    return jdbc.queryForObject(
        "SELECT id FROM flash_sale_campaigns WHERE name = ?", Long.class, name);
  }

  public void setCampaignStatus(long campaignId, String status) {
    jdbc.update("UPDATE flash_sale_campaigns SET status = ? WHERE id = ?", status, campaignId);
  }

  public void item(long campaignId, long productId) {
    jdbc.update(
        "INSERT INTO flash_sale_items (campaign_id, product_id, sale_price, total_stock)"
            + " VALUES (?, ?, 1.00, 1)",
        campaignId,
        productId);
  }
}
