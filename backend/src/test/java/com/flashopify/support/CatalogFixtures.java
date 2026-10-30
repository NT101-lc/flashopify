package com.flashopify.support;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts catalog rows with random slugs/SKUs so tests never depend on empty shared tables. */
public final class CatalogFixtures {

  private final JdbcTemplate jdbc;

  public CatalogFixtures(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public static String rnd() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  public long category(String name, Long parentId) {
    String slug = "cat-" + rnd();
    jdbc.update(
        "INSERT INTO categories (parent_id, slug, name) VALUES (?, ?, ?)", parentId, slug, name);
    return jdbc.queryForObject("SELECT id FROM categories WHERE slug = ?", Long.class, slug);
  }

  public String categorySlug(long id) {
    return jdbc.queryForObject("SELECT slug FROM categories WHERE id = ?", String.class, id);
  }

  public long product(
      long categoryId,
      String name,
      String brand,
      String price,
      String rating,
      String status,
      LocalDateTime createdAt,
      String description) {
    String slug = "p-" + rnd();
    jdbc.update(
        "INSERT INTO products (category_id, sku, name, slug, description, brand, price, rating,"
            + " status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        categoryId,
        "SKU-" + rnd(),
        name,
        slug,
        description,
        brand,
        new BigDecimal(price),
        rating == null ? null : new BigDecimal(rating),
        status,
        createdAt);
    return jdbc.queryForObject("SELECT id FROM products WHERE slug = ?", Long.class, slug);
  }

  public long product(long categoryId, String status) {
    return product(
        categoryId, "Item " + rnd(), "Brand", "10.00", "4.00", status, LocalDateTime.now(), null);
  }

  public String slugOf(long productId) {
    return jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, productId);
  }

  public void inventory(long productId, int quantity, int reserved) {
    jdbc.update(
        "INSERT INTO inventory (product_id, quantity, reserved) VALUES (?, ?, ?)",
        productId,
        quantity,
        reserved);
  }
}
