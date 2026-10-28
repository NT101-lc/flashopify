package com.flashopify.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.product.service.ProductDetailCache;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.CatalogFixtures;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Smoke test of the admin product API against real MySQL and Redis. */
class AdminProductFlowIT extends AbstractIntegrationTest {

  private static final String BASE = "/api/v1/admin/products";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;

  CatalogFixtures fx;
  long cat;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
    cat = fx.category("Admin cat", null);
  }

  private static RequestPostProcessor admin() {
    return jwt().jwt(j -> j.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private String body(long categoryId, String sku, String slug, String status, Integer stock)
      throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("categoryId", categoryId);
    m.put("sku", sku);
    m.put("name", "Name " + slug);
    m.put("slug", slug);
    m.put("brand", "Brand");
    m.put("price", "12.50");
    m.put("status", status);
    if (stock != null) {
      m.put("initialStock", stock);
    }
    return mapper.writeValueAsString(m);
  }

  private String body(String sku, String slug, String status, Integer stock) throws Exception {
    return body(cat, sku, slug, status, stock);
  }

  private ResultActions send(MockHttpServletRequestBuilder req) throws Exception {
    return mvc.perform(req.with(admin()).contentType(MediaType.APPLICATION_JSON));
  }

  private long createProduct(String sku, String slug, int stock) throws Exception {
    JsonNode n =
        mapper.readTree(
            send(post(BASE).content(body(sku, slug, "ACTIVE", stock)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return n.get("id").asLong();
  }

  @Test
  void createReadsBackWithInventoryAndRejectsDuplicates() throws Exception {
    String sfx = CatalogFixtures.rnd();
    send(post(BASE).content(body("SKU-" + sfx, "slug-" + sfx, "INACTIVE", 7)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.price").value("12.50"))
        .andExpect(jsonPath("$.status").value("INACTIVE"))
        .andExpect(jsonPath("$.inventory.quantity").value(7))
        .andExpect(jsonPath("$.inventory.available").value(7))
        .andExpect(jsonPath("$.inventory.version").value(0));

    send(post(BASE).content(body("SKU-" + sfx, "other-" + sfx, "ACTIVE", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    send(post(BASE).content(body("SKU2-" + sfx, "slug-" + sfx, "ACTIVE", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SLUG"));
    // the failed creates left nothing behind
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE sku IN (?, ?)",
                Integer.class,
                "SKU2-" + sfx,
                "SKU-" + sfx))
        .isEqualTo(1);
  }

  @Test
  void validationRejectsReservedSlugDeletedStatusAndUnknownCategory() throws Exception {
    String sfx = CatalogFixtures.rnd();
    send(post(BASE).content(body("SKU-" + sfx, "brands", "ACTIVE", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("slug"));
    send(post(BASE).content(body("SKU-" + sfx, "s-" + sfx, "DELETED", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    send(post(BASE).content(body(Long.MAX_VALUE, "SKU-" + sfx, "s-" + sfx, "ACTIVE", null)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
  }

  @Test
  void updateEvictsOldAndNewSlugAfterCommit() throws Exception {
    String sfx = CatalogFixtures.rnd();
    long id = createProduct("SKU-" + sfx, "old-" + sfx, 5);
    redis.opsForValue().set(ProductDetailCache.key("old-" + sfx), "x");
    redis.opsForValue().set(ProductDetailCache.key("new-" + sfx), "x");

    send(put(BASE + "/" + id).content(body("SKU-" + sfx, "new-" + sfx, "ACTIVE", 999)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("new-" + sfx))
        .andExpect(jsonPath("$.inventory.quantity").value(5));

    assertThat(redis.hasKey(ProductDetailCache.key("old-" + sfx))).isFalse();
    assertThat(redis.hasKey(ProductDetailCache.key("new-" + sfx))).isFalse();
  }

  @Test
  void deleteIsSoftIdempotentAndBlockedByActiveCampaign() throws Exception {
    String sfx = CatalogFixtures.rnd();
    long id = createProduct("SKU-" + sfx, "del-" + sfx, 5);
    long blocked = createProduct("SKU-B" + sfx, "blk-" + sfx, 5);

    String email = "adm-" + sfx + "@example.com";
    jdbc.update(
        "INSERT INTO users (email, password_hash, full_name) VALUES (?, 'x', 'Admin')", email);
    long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    jdbc.update(
        "INSERT INTO flash_sale_campaigns (name, start_time, end_time, status, created_by)"
            + " VALUES ('c', NOW(), NOW() + INTERVAL 1 DAY, 'SCHEDULED', ?)",
        userId);
    long campaignId =
        jdbc.queryForObject(
            "SELECT id FROM flash_sale_campaigns WHERE created_by = ?", Long.class, userId);
    jdbc.update(
        "INSERT INTO flash_sale_items (campaign_id, product_id, sale_price, total_stock)"
            + " VALUES (?, ?, 1.00, 1)",
        campaignId,
        blocked);

    send(delete(BASE + "/" + blocked))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRODUCT_IN_ACTIVE_CAMPAIGN"));
    jdbc.update("UPDATE flash_sale_campaigns SET status = 'ENDED' WHERE id = ?", campaignId);
    send(delete(BASE + "/" + blocked)).andExpect(status().isNoContent());

    send(delete(BASE + "/" + id)).andExpect(status().isNoContent());
    send(delete(BASE + "/" + id)).andExpect(status().isNoContent());
    send(get(BASE + "/" + id)).andExpect(jsonPath("$.status").value("DELETED"));
    send(delete(BASE + "/" + Long.MAX_VALUE)).andExpect(status().isNotFound());
    send(put(BASE + "/" + id).content(body("SKU-" + sfx, "del-" + sfx, "ACTIVE", null)))
        .andExpect(status().isNotFound());
    send(put(BASE + "/" + id + "/inventory").content("{\"quantity\":9,\"version\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"));
  }

  @Test
  void inventoryUpdateIsVersionedAndNeverBelowReserved() throws Exception {
    String sfx = CatalogFixtures.rnd();
    long id = createProduct("SKU-" + sfx, "inv-" + sfx, 10);
    jdbc.update(
        "UPDATE inventory SET reserved = 4, version = version + 1 WHERE product_id = ?", id);

    send(put(BASE + "/" + id + "/inventory").content("{\"quantity\":20,\"version\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    send(put(BASE + "/" + id + "/inventory").content("{\"quantity\":3,\"version\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    send(put(BASE + "/" + id + "/inventory").content("{\"quantity\":-1,\"version\":1}"))
        .andExpect(status().isBadRequest());
    send(put(BASE + "/" + id + "/inventory").content("{\"quantity\":4,\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quantity").value(4))
        .andExpect(jsonPath("$.available").value(0))
        .andExpect(jsonPath("$.version").value(2));
    send(put(BASE + "/" + Long.MAX_VALUE + "/inventory").content("{\"quantity\":1,\"version\":0}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void listFiltersAndSortsByAvailable() throws Exception {
    String sfx = CatalogFixtures.rnd();
    long low = createProduct("SKU-L" + sfx, "low-" + sfx, 2);
    long high = createProduct("SKU-H" + sfx, "high-" + sfx, 50);
    long none = fx.product(cat, "ACTIVE");

    JsonNode res =
        mapper.readTree(
            send(get(BASE).param("categoryId", String.valueOf(cat)).param("sort", "available,asc"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(res.get("totalItems").asInt()).isEqualTo(3);
    assertThat(res.get("items").get(0).get("id").asLong()).isEqualTo(none);
    assertThat(res.get("items").get(1).get("id").asLong()).isEqualTo(low);
    assertThat(res.get("items").get(2).get("id").asLong()).isEqualTo(high);

    send(get(BASE).param("categoryId", String.valueOf(cat)).param("lowStock", "2"))
        .andExpect(jsonPath("$.totalItems").value(2));
    send(get(BASE).param("q", "SKU-H" + sfx)).andExpect(jsonPath("$.totalItems").value(1));
    send(get(BASE).param("sort", "bogus")).andExpect(status().isBadRequest());
  }

  @Test
  void adminEndpointsRequireAdminRole() throws Exception {
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    mvc.perform(
            get(BASE)
                .with(
                    jwt()
                        .jwt(j -> j.subject("2"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isForbidden());
  }
}
