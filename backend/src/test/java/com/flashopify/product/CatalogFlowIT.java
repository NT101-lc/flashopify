package com.flashopify.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.product.service.ProductDetailCache;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.CatalogFixtures;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class CatalogFlowIT extends AbstractIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ProductDetailCache cache;

  CatalogFixtures fx;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
  }

  private JsonNode getJson(String url) throws Exception {
    return mapper.readTree(
        mvc.perform(get(url))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  // ---- TC-5 ----

  @Test
  void detailIsCachedStaticPartWithLiveStock() throws Exception {
    long cat = fx.category("Phones", null);
    long id = fx.product(cat, "ACTIVE");
    fx.inventory(id, 10, 2);
    String slug = fx.slugOf(id);
    String key = ProductDetailCache.key(slug);

    mvc.perform(get("/api/v1/products/" + slug))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.price").value("10.00"))
        .andExpect(jsonPath("$.availableQuantity").value(8))
        .andExpect(jsonPath("$.inStock").value(true))
        .andExpect(jsonPath("$.flashSale").doesNotExist())
        .andExpect(jsonPath("$.category.slug").value(fx.categorySlug(cat)));

    assertThat(redis.hasKey(key)).isTrue();
    Long ttl = redis.getExpire(key);
    assertThat(ttl).isPositive().isLessThanOrEqualTo(600);

    jdbc.update("UPDATE products SET name = 'Renamed' WHERE id = ?", id);
    assertThat(getJson("/api/v1/products/" + slug).get("name").asText()).startsWith("Item ");

    jdbc.update("UPDATE inventory SET quantity = 50 WHERE product_id = ?", id);
    JsonNode live = getJson("/api/v1/products/" + slug);
    assertThat(live.get("availableQuantity").asInt()).isEqualTo(48);
    assertThat(live.get("name").asText()).startsWith("Item ");

    cache.evict(List.of(slug));
    assertThat(redis.hasKey(key)).isFalse();
    assertThat(getJson("/api/v1/products/" + slug).get("name").asText()).isEqualTo("Renamed");
  }

  @Test
  void productWithoutInventoryRowIsOutOfStock() throws Exception {
    long cat = fx.category("NoStock", null);
    String slug = fx.slugOf(fx.product(cat, "ACTIVE"));
    mvc.perform(get("/api/v1/products/" + slug))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableQuantity").value(0))
        .andExpect(jsonPath("$.inStock").value(false));
  }

  // ---- TC-6 ----

  @Test
  void inactiveAndDeletedProductsAreHiddenEverywhere() throws Exception {
    long cat = fx.category("Hidden", null);
    String catSlug = fx.categorySlug(cat);
    long active = fx.product(cat, "ACTIVE");
    long off = fx.product(cat, "INACTIVE");
    long gone = fx.product(cat, "DELETED");
    jdbc.update("UPDATE products SET brand = 'OnlyInactive' WHERE id = ?", off);
    jdbc.update("UPDATE products SET brand = 'OnlyActive' WHERE id = ?", active);

    JsonNode list = getJson("/api/v1/products?categorySlug=" + catSlug);
    assertThat(list.get("totalItems").asInt()).isEqualTo(1);
    assertThat(list.get("items").get(0).get("id").asLong()).isEqualTo(active);

    JsonNode brands = getJson("/api/v1/products/brands?categorySlug=" + catSlug);
    assertThat(brands).hasSize(1);
    assertThat(brands.get(0).asText()).isEqualTo("OnlyActive");

    for (long id : new long[] {off, gone}) {
      mvc.perform(get("/api/v1/products/" + fx.slugOf(id)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
          .andExpect(jsonPath("$.message").exists())
          .andExpect(jsonPath("$.traceId").exists())
          .andExpect(jsonPath("$.timestamp").exists());
      assertThat(redis.hasKey(ProductDetailCache.key(fx.slugOf(id)))).isFalse();
    }
    mvc.perform(get("/api/v1/products/does-not-exist-" + CatalogFixtures.rnd()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

    JsonNode node = findCategory(getJson("/api/v1/categories"), catSlug);
    assertThat(node.get("productCount").asInt()).isEqualTo(1);

    // After deactivation and eviction the previously cached product disappears too.
    String activeSlug = fx.slugOf(active);
    mvc.perform(get("/api/v1/products/" + activeSlug)).andExpect(status().isOk());
    jdbc.update("UPDATE products SET status = 'INACTIVE' WHERE id = ?", active);
    cache.evict(List.of(activeSlug));
    mvc.perform(get("/api/v1/products/" + activeSlug)).andExpect(status().isNotFound());
  }

  // ---- TC-7 ----

  @Test
  void paginationReportsCorrectTotals() throws Exception {
    long cat = fx.category("Paged", null);
    for (int i = 0; i < 25; i++) {
      fx.product(cat, "ACTIVE");
    }
    String url = "/api/v1/products?size=10&categorySlug=" + fx.categorySlug(cat) + "&page=";
    int[] expected = {10, 10, 5};
    for (int p = 0; p < 3; p++) {
      JsonNode page = getJson(url + p);
      assertThat(page.get("items")).hasSize(expected[p]);
      assertThat(page.get("page").asInt()).isEqualTo(p);
      assertThat(page.get("size").asInt()).isEqualTo(10);
      assertThat(page.get("totalItems").asLong()).isEqualTo(25);
      assertThat(page.get("totalPages").asInt()).isEqualTo(3);
    }
    JsonNode beyond = getJson(url + 5);
    assertThat(beyond.get("items")).isEmpty();
    assertThat(beyond.get("totalItems").asLong()).isEqualTo(25);
    assertThat(beyond.get("totalPages").asInt()).isEqualTo(3);
    assertThat(beyond.get("page").asInt()).isEqualTo(5);
  }

  @Test
  void unknownCategoryGivesEmptyPage() throws Exception {
    JsonNode page = getJson("/api/v1/products?categorySlug=nope-" + CatalogFixtures.rnd());
    assertThat(page.get("items")).isEmpty();
    assertThat(page.get("totalItems").asLong()).isZero();
    assertThat(page.get("totalPages").asInt()).isZero();
  }

  @Test
  void minPriceAboveMaxPriceIsRejected() throws Exception {
    mvc.perform(get("/api/v1/products?minPrice=50&maxPrice=10"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  // ---- TC-8 ----

  @Test
  void categoryTreeCountsDescendantsAndParentSlugFilterIncludesChildren() throws Exception {
    long parent = fx.category("Parent", null);
    long child = fx.category("Child", parent);
    long c1 = fx.product(child, "ACTIVE");
    long c2 = fx.product(child, "ACTIVE");
    fx.product(child, "INACTIVE");
    String parentSlug = fx.categorySlug(parent);

    JsonNode parentNode = findCategory(getJson("/api/v1/categories"), parentSlug);
    assertThat(parentNode.get("productCount").asInt()).isEqualTo(2);
    assertThat(parentNode.get("children")).hasSize(1);
    JsonNode childNode = parentNode.get("children").get(0);
    assertThat(childNode.get("slug").asText()).isEqualTo(fx.categorySlug(child));
    assertThat(childNode.get("productCount").asInt()).isEqualTo(2);
    assertThat(childNode.get("children")).isEmpty();

    JsonNode page = getJson("/api/v1/products?categorySlug=" + parentSlug);
    List<Long> ids =
        StreamSupport.stream(page.get("items").spliterator(), false)
            .map(n -> n.get("id").asLong())
            .toList();
    assertThat(ids).containsExactlyInAnyOrder(c1, c2);
  }

  private static JsonNode findCategory(JsonNode roots, String slug) {
    for (JsonNode n : roots) {
      if (slug.equals(n.get("slug").asText())) {
        return n;
      }
    }
    throw new AssertionError("category not in tree: " + slug);
  }
}
