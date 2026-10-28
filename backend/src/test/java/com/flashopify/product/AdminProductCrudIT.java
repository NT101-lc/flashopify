package com.flashopify.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.AdminProductClient;
import com.flashopify.support.CatalogFixtures;
import com.flashopify.support.FlashSaleFixtures;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** FLS-009 admin product CRUD: create, uniqueness, validation, soft delete, campaign guard. */
class AdminProductCrudIT extends AbstractIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  private CatalogFixtures fx;
  private FlashSaleFixtures flash;
  private AdminProductClient admin;
  private long cat;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
    flash = new FlashSaleFixtures(jdbc);
    admin = new AdminProductClient(mvc, mapper);
    cat = fx.category("Crud " + CatalogFixtures.rnd(), null);
  }

  private static String rnd() {
    return CatalogFixtures.rnd();
  }

  private int countProductsBySku(String sku) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE sku = ?", Integer.class, sku);
  }

  private int countProductsBySlug(String slug) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE slug = ?", Integer.class, slug);
  }

  private int countInventoryForSku(String sku) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM inventory i JOIN products p ON p.id = i.product_id WHERE p.sku = ?",
        Integer.class,
        sku);
  }

  private String statusOf(long productId) {
    return jdbc.queryForObject("SELECT status FROM products WHERE id = ?", String.class, productId);
  }

  private String slugInDb(long productId) {
    return jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, productId);
  }

  private List<Long> publicListIds(String categorySlug) throws Exception {
    JsonNode page =
        mapper.readTree(
            mvc.perform(
                    get("/api/v1/products")
                        .param("categorySlug", categorySlug)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    List<Long> ids = new ArrayList<>();
    page.get("items").forEach(n -> ids.add(n.get("id").asLong()));
    return ids;
  }

  @Test
  void tc1CreateReturns201WithDetailAndInventoryAndOneRowEach() throws Exception {
    String sfx = rnd();
    String sku = "SKU-" + sfx;
    String slug = "tc1-" + sfx;

    JsonNode n =
        admin.parse(
            admin
                .create(AdminProductClient.upsert(cat, sku, slug, "ACTIVE", 50))
                .andExpect(status().isCreated()));

    long id = n.get("id").asLong();
    assertThat(id).isPositive();
    assertThat(n.get("sku").asText()).isEqualTo(sku);
    assertThat(n.get("name").asText()).isEqualTo("Name " + slug);
    assertThat(n.get("slug").asText()).isEqualTo(slug);
    assertThat(n.get("brand").asText()).isEqualTo("Brand");
    assertThat(n.get("price").asText()).isEqualTo("12.50");
    assertThat(n.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(n.get("categoryId").asLong()).isEqualTo(cat);
    assertThat(n.get("images").isArray()).isTrue();
    assertThat(n.get("images").size()).isZero();
    assertThat(n.get("createdAt").isTextual()).isTrue();
    assertThat(n.get("updatedAt").isTextual()).isTrue();

    JsonNode inv = n.get("inventory");
    assertThat(inv.get("quantity").asInt()).isEqualTo(50);
    assertThat(inv.get("reserved").asInt()).isEqualTo(0);
    assertThat(inv.get("available").asInt()).isEqualTo(50);
    assertThat(inv.get("version").asInt()).isEqualTo(0);

    assertThat(countProductsBySku(sku)).isEqualTo(1);
    assertThat(countInventoryForSku(sku)).isEqualTo(1);
  }

  @Test
  void tc2DuplicateSkuAndSlugOnCreateAndUpdateReturn409WithoutSideEffects() throws Exception {
    String sfx = rnd();
    String pSku = "SKU-P-" + sfx;
    String pSlug = "p-slug-" + sfx;
    long p = admin.createOk(cat, pSku, pSlug, 5);
    long q = admin.createOk(cat, "SKU-Q-" + sfx, "q-slug-" + sfx, 5);
    String newSku = "SKU-N-" + sfx;
    String newSlug = "n-slug-" + sfx;

    admin
        .create(AdminProductClient.upsert(cat, pSku, newSlug, "ACTIVE", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    admin
        .create(AdminProductClient.upsert(cat, newSku, pSlug, "ACTIVE", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SLUG"));
    admin
        .update(q, AdminProductClient.upsert(cat, pSku, "q-moved-" + sfx, "ACTIVE", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    admin
        .update(q, AdminProductClient.upsert(cat, "SKU-QQ-" + sfx, pSlug, "ACTIVE", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SLUG"));

    // No new product or inventory rows, and the existing rows were not touched.
    assertThat(countProductsBySku(newSku)).isZero();
    assertThat(countProductsBySlug(newSlug)).isZero();
    assertThat(countProductsBySku("SKU-QQ-" + sfx)).isZero();
    assertThat(countProductsBySku(pSku)).isEqualTo(1);
    assertThat(countInventoryForSku(pSku)).isEqualTo(1);
    assertThat(slugInDb(q)).isEqualTo("q-slug-" + sfx);
    assertThat(slugInDb(p)).isEqualTo(pSlug);
  }

  @Test
  void tc3UnknownCategoryIs404AndInvalidFieldsAre400WithFieldErrors() throws Exception {
    String sfx = rnd();
    String sku = "SKU-V-" + sfx;

    admin
        .create(AdminProductClient.upsert(Long.MAX_VALUE, sku, "v-" + sfx, "ACTIVE", null))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

    admin
        .create(AdminProductClient.upsert(cat, sku, "Bad Slug", "ACTIVE", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("slug")));

    admin
        .create(AdminProductClient.upsert(cat, sku, "brands", "ACTIVE", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("slug")));

    Map<String, Object> negativePrice =
        AdminProductClient.upsert(cat, sku, "neg-" + sfx, "ACTIVE", null);
    negativePrice.put("price", new BigDecimal("-1"));
    admin
        .create(negativePrice)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("price")));

    Map<String, Object> tooManyDecimals =
        AdminProductClient.upsert(cat, sku, "dec-" + sfx, "ACTIVE", null);
    tooManyDecimals.put("price", new BigDecimal("1.234"));
    admin
        .create(tooManyDecimals)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("price")));

    admin
        .create(AdminProductClient.upsert(cat, sku, "del-" + sfx, "DELETED", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("status")));

    assertThat(countProductsBySku(sku)).isZero();
  }

  @Test
  void tc4SoftDeleteHidesFromPublicButAdminStillReadsDeletedRow() throws Exception {
    String sfx = rnd();
    String slug = "soft-" + sfx;
    long id = admin.createOk(cat, "SKU-S-" + sfx, slug, 5);
    long other = admin.createOk(cat, "SKU-O-" + sfx, "other-" + sfx, 5);
    String catSlug = fx.categorySlug(cat);

    assertThat(publicListIds(catSlug)).contains(id, other);

    admin.delete(id).andExpect(status().isNoContent());

    // Row still exists, status is DELETED.
    assertThat(statusOf(id)).isEqualTo("DELETED");
    mvc.perform(get("/api/v1/products/" + slug)).andExpect(status().isNotFound());
    assertThat(publicListIds(catSlug)).doesNotContain(id).contains(other);

    admin.get(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"));
    admin
        .update(id, AdminProductClient.upsert(cat, "SKU-S-" + sfx, slug, "ACTIVE", null))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  void tc5DeleteBlockedWhileCampaignScheduledOrRunningAndAllowedWhenEndedOrCancelled()
      throws Exception {
    long owner = flash.adminUser();
    long scheduledProduct = admin.createOk(cat, "SKU-G-" + rnd(), "g-" + rnd(), 5);
    long campaign = flash.campaign(owner, "SCHEDULED");
    flash.item(campaign, scheduledProduct);

    admin
        .delete(scheduledProduct)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRODUCT_IN_ACTIVE_CAMPAIGN"));
    assertThat(statusOf(scheduledProduct)).isEqualTo("ACTIVE");

    flash.setCampaignStatus(campaign, "RUNNING");
    admin
        .delete(scheduledProduct)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRODUCT_IN_ACTIVE_CAMPAIGN"));
    assertThat(statusOf(scheduledProduct)).isEqualTo("ACTIVE");

    flash.setCampaignStatus(campaign, "ENDED");
    admin.delete(scheduledProduct).andExpect(status().isNoContent());
    assertThat(statusOf(scheduledProduct)).isEqualTo("DELETED");

    long cancelledProduct = admin.createOk(cat, "SKU-C-" + rnd(), "c-" + rnd(), 5);
    long cancelled = flash.campaign(owner, "CANCELLED");
    flash.item(cancelled, cancelledProduct);
    admin.delete(cancelledProduct).andExpect(status().isNoContent());
    assertThat(statusOf(cancelledProduct)).isEqualTo("DELETED");
  }

  @Test
  void tc12DuplicateRequestsAreSafeWithoutIdempotencyKey() throws Exception {
    // Idempotency-Key is not required here: these are not purchase endpoints. Duplicate delete is
    // a no-op, and a duplicate inventory write is rejected by the version check.
    long id = admin.createOk(cat, "SKU-D-" + rnd(), "d-" + rnd(), 5);
    admin.delete(id).andExpect(status().isNoContent());
    Timestamp updatedAfterFirst =
        jdbc.queryForObject("SELECT updated_at FROM products WHERE id = ?", Timestamp.class, id);
    Thread.sleep(1100); // updated_at has 1-second resolution; a second write would show up
    admin.delete(id).andExpect(status().isNoContent());
    Timestamp updatedAfterSecond =
        jdbc.queryForObject("SELECT updated_at FROM products WHERE id = ?", Timestamp.class, id);
    assertThat(statusOf(id)).isEqualTo("DELETED");
    assertThat(updatedAfterSecond).isEqualTo(updatedAfterFirst);

    long invProduct = fx.product(cat, "ACTIVE");
    fx.inventory(invProduct, 10, 0);
    admin
        .putInventory(invProduct, 20, 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    admin
        .putInventory(invProduct, 20, 0)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM inventory WHERE product_id = ?", Integer.class, invProduct))
        .isEqualTo(1);
  }

  @Test
  void tc18UnknownProductIs404AndInventoryOnDeletedProductIs409() throws Exception {
    long missing = Long.MAX_VALUE;
    admin
        .get(missing)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    admin
        .update(
            missing, AdminProductClient.upsert(cat, "SKU-M-" + rnd(), "m-" + rnd(), "ACTIVE", null))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    admin
        .delete(missing)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    admin
        .putInventory(missing, 1, 0)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

    String sku = "SKU-X-" + rnd();
    long id = admin.createOk(cat, sku, "x-" + rnd(), 5);
    admin.delete(id).andExpect(status().isNoContent());
    admin
        .putInventory(id, 9, 0)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"));
    assertThat(
            jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE product_id = ?", Integer.class, id))
        .isEqualTo(5);
  }

  @Test
  void tc20StatusToInactiveBlockedWhileScheduledAndAllowedOnceEnded() throws Exception {
    long owner = flash.adminUser();
    String sfx = rnd();
    String sku = "SKU-T20-" + sfx;
    String slug = "t20-" + sfx;
    long id = admin.createOk(cat, sku, slug, 5);
    long campaign = flash.campaign(owner, "SCHEDULED");
    flash.item(campaign, id);

    admin
        .update(id, AdminProductClient.upsert(cat, sku, slug, "INACTIVE", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRODUCT_IN_ACTIVE_CAMPAIGN"));
    assertThat(statusOf(id)).isEqualTo("ACTIVE");

    flash.setCampaignStatus(campaign, "ENDED");
    admin
        .update(id, AdminProductClient.upsert(cat, sku, slug, "INACTIVE", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INACTIVE"));
    assertThat(statusOf(id)).isEqualTo("INACTIVE");
  }
}
