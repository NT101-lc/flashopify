package com.flashopify.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.product.service.CatalogProperties;
import com.flashopify.product.service.ProductCacheEvictionListener;
import com.flashopify.product.service.ProductDetailCache;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.AdminProductClient;
import com.flashopify.support.CatalogFixtures;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** FLS-009 cache/DB drift: the DB is the source of truth, Redis is only a time-bounded copy. */
class AdminProductCacheIT extends AbstractIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired CatalogProperties catalogProperties;

  @MockitoSpyBean ProductDetailCache detailCache;

  private CatalogFixtures fx;
  private AdminProductClient admin;
  private long cat;
  private ListAppender<ILoggingEvent> warnings;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
    admin = new AdminProductClient(mvc, mapper);
    cat = fx.category("Cache " + CatalogFixtures.rnd(), null);
  }

  @AfterEach
  void detachAppender() {
    if (warnings != null) {
      listenerLogger().detachAppender(warnings);
      warnings = null;
    }
  }

  private Logger listenerLogger() {
    return (Logger) LoggerFactory.getLogger(ProductCacheEvictionListener.class);
  }

  private JsonNode publicDetail(String slug, int expectedStatus) throws Exception {
    String body =
        mvc.perform(get("/api/v1/products/" + slug))
            .andExpect(status().is(expectedStatus))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body.isBlank() ? null : mapper.readTree(body);
  }

  private boolean cached(String slug) {
    return Boolean.TRUE.equals(redis.hasKey(ProductDetailCache.key(slug)));
  }

  @Test
  void tc14AdminWritesOverrideCachedDetailAndStockIsLiveWithoutEvict() throws Exception {
    String sfx = CatalogFixtures.rnd();
    String slugA = "ca-" + sfx;
    String slugB = "cb-" + sfx;
    String sku = "SKU-CA-" + sfx;
    long id = admin.createOk(cat, sku, slugA, 5);

    publicDetail(slugA, 200);
    assertThat(cached(slugA)).isTrue();

    var rename = AdminProductClient.upsert(cat, sku, slugB, "ACTIVE", null);
    rename.put("name", "Renamed " + sfx);
    admin.update(id, rename).andExpect(status().isOk());
    assertThat(cached(slugA)).as("old slug key evicted after commit").isFalse();
    publicDetail(slugA, 404);
    JsonNode renamedDetail = publicDetail(slugB, 200);
    assertThat(renamedDetail.get("name").asText()).isEqualTo("Renamed " + sfx);

    var inactive = AdminProductClient.upsert(cat, sku, slugB, "INACTIVE", null);
    inactive.put("name", "Renamed " + sfx);
    admin.update(id, inactive).andExpect(status().isOk());
    publicDetail(slugB, 404);

    var active = AdminProductClient.upsert(cat, sku, slugB, "ACTIVE", null);
    active.put("name", "Renamed " + sfx);
    admin.update(id, active).andExpect(status().isOk());
    JsonNode warm = publicDetail(slugB, 200);
    assertThat(warm.get("availableQuantity").asInt()).isEqualTo(5);
    assertThat(cached(slugB)).isTrue();

    // Stock is overlaid live, so an inventory write must not need (or cause) an eviction.
    admin.putInventory(id, 25, 0).andExpect(status().isOk());
    assertThat(cached(slugB)).as("inventory write does not evict").isTrue();
    JsonNode live = publicDetail(slugB, 200);
    assertThat(live.get("availableQuantity").asInt()).isEqualTo(25);
    assertThat(live.get("inStock").asBoolean()).isTrue();
  }

  @Test
  void f3CreateEvictsStaleCachedSlugSoPublicGetShowsNewProduct() throws Exception {
    String sfx = CatalogFixtures.rnd();
    String slug = "f3-" + sfx;
    String sku = "SKU-F3-" + sfx;
    // A stale copy left behind for a slug that does not exist in the DB yet.
    redis
        .opsForValue()
        .set(ProductDetailCache.key(slug), "{\"name\":\"STALE\"}", Duration.ofMinutes(10));
    assertThat(cached(slug)).isTrue();

    admin
        .create(AdminProductClient.upsert(cat, sku, slug, "ACTIVE", 5))
        .andExpect(status().isCreated());

    assertThat(cached(slug)).as("stale key evicted after create commit").isFalse();
    JsonNode fresh = publicDetail(slug, 200);
    assertThat(fresh.get("name").asText()).isEqualTo("Name " + slug);
    assertThat(fresh.get("sku").asText()).isEqualTo(sku);
  }

  @Test
  void tc14CacheEvictFailureDoesNotFailAdminWriteAndStaleKeyExpiresWithinTtl() throws Exception {
    String sfx = CatalogFixtures.rnd();
    String slugA = "fa-" + sfx;
    String slugB = "fb-" + sfx;
    String sku = "SKU-FA-" + sfx;
    long id = admin.createOk(cat, sku, slugA, 5);
    publicDetail(slugA, 200);
    assertThat(cached(slugA)).isTrue();

    doThrow(new RedisConnectionFailureException("redis unavailable"))
        .when(detailCache)
        .evict(anyCollection());
    warnings = new ListAppender<>();
    warnings.start();
    listenerLogger().addAppender(warnings);

    var rename = AdminProductClient.upsert(cat, sku, slugB, "ACTIVE", null);
    rename.put("name", "Renamed " + sfx);
    admin.update(id, rename).andExpect(status().isOk());

    // The DB change is committed even though the eviction failed.
    assertThat(jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, id))
        .isEqualTo(slugB);
    assertThat(
            warnings.list.stream()
                .anyMatch(
                    e ->
                        e.getLevel() == Level.WARN
                            && e.getFormattedMessage()
                                .contains("Cache eviction failed for product " + id)))
        .as("a WARN is logged for the failed eviction")
        .isTrue();

    // The stale key survives but its TTL bounds how long it can be served.
    Long ttl = redis.getExpire(ProductDetailCache.key(slugA), TimeUnit.SECONDS);
    assertThat(ttl).isNotNull();
    assertThat(ttl).isPositive();
    assertThat(ttl).isLessThanOrEqualTo(catalogProperties.cache().detailTtl().toSeconds());
  }
}
