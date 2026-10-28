package com.flashopify.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.AdminProductClient;
import com.flashopify.support.CatalogFixtures;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FLS-009 admin list: filters, sorts, paging. Every test uses its own category so the shared tables
 * never change the expected results.
 */
class AdminProductListIT extends AbstractIntegrationTest {

  private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  private CatalogFixtures fx;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
  }

  private static String rnd() {
    return CatalogFixtures.rnd();
  }

  /** Inserts a product with explicit sku, brand, price and timestamps. */
  private long insert(
      long categoryId,
      String name,
      String sku,
      String brand,
      String price,
      String status,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    String slug = "lst-" + rnd();
    jdbc.update(
        "INSERT INTO products (category_id, sku, name, slug, brand, price, status, created_at,"
            + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        categoryId,
        sku,
        name,
        slug,
        brand,
        new BigDecimal(price),
        status,
        createdAt,
        updatedAt);
    return jdbc.queryForObject("SELECT id FROM products WHERE slug = ?", Long.class, slug);
  }

  /** GET the admin list with alternating key/value query parameters. */
  private JsonNode list(Object... keyValues) throws Exception {
    MockHttpServletRequestBuilder req =
        get(AdminProductClient.BASE).with(AdminProductClient.admin());
    for (int i = 0; i < keyValues.length; i += 2) {
      req.param(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
    }
    return mapper.readTree(
        mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private static List<Long> ids(JsonNode page) {
    List<Long> ids = new ArrayList<>();
    page.get("items").forEach(n -> ids.add(n.get("id").asLong()));
    return ids;
  }

  @Test
  void tc16FiltersQueryStatusCategoryAndDeletedInclusionWork() throws Exception {
    String t1 = "gz" + rnd(); // matches name
    String t2 = "xq" + rnd(); // matches sku
    String t3 = "br" + rnd(); // matches brand
    long c = fx.category("L16 " + rnd(), null);
    long a = insert(c, "Gizmo " + t1, "SKU-A-" + rnd(), "Acme", "10.00", "ACTIVE", T0, T0);
    long b = insert(c, "Plain " + rnd(), "SKU-" + t2, "Other", "10.00", "INACTIVE", T0, T0);
    long d =
        insert(c, "Plain2 " + rnd(), "SKU-D-" + rnd(), "Brand " + t3, "10.00", "DELETED", T0, T0);
    long c2 = fx.category("L16b " + rnd(), null);
    long e = insert(c2, "Other " + rnd(), "SKU-E-" + rnd(), "Acme", "10.00", "ACTIVE", T0, T0);

    assertThat(ids(list("q", t1, "categoryId", c))).containsExactly(a);
    assertThat(ids(list("q", t2, "categoryId", c))).containsExactly(b);
    assertThat(ids(list("q", t3, "categoryId", c))).containsExactly(d);

    assertThat(ids(list("categoryId", c, "status", "ACTIVE"))).containsExactly(a);
    assertThat(ids(list("categoryId", c, "status", "INACTIVE"))).containsExactly(b);
    assertThat(ids(list("categoryId", c, "status", "DELETED"))).containsExactly(d);

    // Without a status filter DELETED products are included.
    assertThat(ids(list("categoryId", c))).containsExactlyInAnyOrder(a, b, d);

    assertThat(ids(list("categoryId", c2))).containsExactly(e);
  }

  @Test
  void tc16LowStockFilterCountsMissingStockAsZero() throws Exception {
    long c = fx.category("L16L " + rnd(), null);
    long x = insert(c, "x " + rnd(), "SKU-X-" + rnd(), "B", "1.00", "ACTIVE", T0, T0);
    fx.inventory(x, 2, 0); // available 2
    long y = insert(c, "y " + rnd(), "SKU-Y-" + rnd(), "B", "1.00", "ACTIVE", T0, T0);
    fx.inventory(y, 50, 0); // available 50
    long z = insert(c, "z " + rnd(), "SKU-Z-" + rnd(), "B", "1.00", "ACTIVE", T0, T0); // no row
    long w = insert(c, "w " + rnd(), "SKU-W-" + rnd(), "B", "1.00", "ACTIVE", T0, T0);
    fx.inventory(w, 10, 8); // available 2

    assertThat(ids(list("categoryId", c, "lowStock", 5))).containsExactlyInAnyOrder(x, w, z);
    assertThat(ids(list("categoryId", c, "lowStock", 0))).containsExactly(z);
  }

  @Test
  void tc16EachSortProducesExpectedOrder() throws Exception {
    long s = fx.category("L16S " + rnd(), null);
    // X: name Charlie, price 20, created 01-03, updated 03-01, available 10
    long x =
        insert(
            s,
            "Charlie",
            "SKU-SX-" + rnd(),
            "B",
            "20.00",
            "ACTIVE",
            LocalDateTime.of(2026, 1, 3, 0, 0),
            LocalDateTime.of(2026, 3, 1, 0, 0));
    // Y: name Alpha, price 10, created 01-01, updated 04-01, available 5
    long y =
        insert(
            s,
            "Alpha",
            "SKU-SY-" + rnd(),
            "B",
            "10.00",
            "ACTIVE",
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 4, 1, 0, 0));
    // Z: name Bravo, price 30, created 01-02, updated 02-01, available 1
    long z =
        insert(
            s,
            "Bravo",
            "SKU-SZ-" + rnd(),
            "B",
            "30.00",
            "ACTIVE",
            LocalDateTime.of(2026, 1, 2, 0, 0),
            LocalDateTime.of(2026, 2, 1, 0, 0));
    fx.inventory(x, 10, 0);
    fx.inventory(y, 5, 0);
    fx.inventory(z, 1, 0);

    Map<String, List<Long>> expected = new LinkedHashMap<>();
    expected.put("updatedAt,desc", List.of(y, x, z));
    expected.put("createdAt,desc", List.of(x, z, y));
    expected.put("name,asc", List.of(y, z, x));
    expected.put("price,asc", List.of(y, x, z));
    expected.put("price,desc", List.of(z, x, y));
    expected.put("available,asc", List.of(z, y, x));
    for (Map.Entry<String, List<Long>> e : expected.entrySet()) {
      assertThat(ids(list("categoryId", s, "sort", e.getKey())))
          .as("sort %s", e.getKey())
          .containsExactlyElementsOf(e.getValue());
    }
    // The default sort is updatedAt,desc.
    assertThat(ids(list("categoryId", s))).containsExactlyElementsOf(List.of(y, x, z));
  }

  @Test
  void tc16InvalidSortIs400() throws Exception {
    mvc.perform(
            get(AdminProductClient.BASE)
                .with(AdminProductClient.admin())
                .param("sort", "bogus,asc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(
            get(AdminProductClient.BASE)
                .with(AdminProductClient.admin())
                .param("sort", "name,desc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void tc16PageResponseFieldsAndInventoryVersionOnEveryItem() throws Exception {
    long c = fx.category("L16P " + rnd(), null);
    for (int i = 0; i < 3; i++) {
      long pid = insert(c, "p" + i + " " + rnd(), "SKU-P-" + rnd(), "B", "1.00", "ACTIVE", T0, T0);
      fx.inventory(pid, 10 + i, 0);
      jdbc.update("UPDATE inventory SET version = ? WHERE product_id = ?", i + 1, pid);
    }

    JsonNode first = list("categoryId", c, "size", 2, "page", 0);
    assertThat(first.get("page").asInt()).isEqualTo(0);
    assertThat(first.get("size").asInt()).isEqualTo(2);
    assertThat(first.get("totalItems").asLong()).isEqualTo(3);
    assertThat(first.get("totalPages").asInt()).isEqualTo(2);
    assertThat(first.get("items").size()).isEqualTo(2);

    JsonNode second = list("categoryId", c, "size", 2, "page", 1);
    assertThat(second.get("items").size()).isEqualTo(1);
    assertThat(second.get("totalItems").asLong()).isEqualTo(3);

    for (JsonNode page : List.of(first, second)) {
      for (JsonNode item : page.get("items")) {
        assertThat(item.get("inventory").get("version").isInt()).isTrue();
      }
    }
  }
}
