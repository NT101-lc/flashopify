package com.flashopify.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.inventory.service.InventoryService;
import com.flashopify.product.service.ProductCacheEvictionListener;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.AdminProductClient;
import com.flashopify.support.CatalogFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FLS-009 crash midway: a fault inside the admin write transaction must roll back every row and
 * must not publish the cache-eviction event. Faults are injected with Mockito spies.
 */
class AdminProductTransactionIT extends AbstractIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @MockitoSpyBean InventoryService inventoryService;
  @MockitoSpyBean ProductCacheEvictionListener evictionListener;

  private CatalogFixtures fx;
  private AdminProductClient admin;
  private long cat;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
    admin = new AdminProductClient(mvc, mapper);
    cat = fx.category("Tx " + CatalogFixtures.rnd(), null);
  }

  @Test
  void tc13CrashAfterProductInsertRollsBackAndRetrySucceedsOnce() throws Exception {
    String sfx = CatalogFixtures.rnd();
    String sku = "SKU-CRASH-" + sfx;
    String slug = "crash-" + sfx;
    int inventoryRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM inventory", Integer.class);

    doThrow(new IllegalStateException("simulated crash after product insert"))
        .when(inventoryService)
        .initialize(anyLong(), anyInt());
    admin
        .create(AdminProductClient.upsert(cat, sku, slug, "ACTIVE", 5))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE sku = ?", Integer.class, sku))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory", Integer.class))
        .isEqualTo(inventoryRowsBefore);

    reset(inventoryService); // fault removed
    admin
        .create(AdminProductClient.upsert(cat, sku, slug, "ACTIVE", 5))
        .andExpect(status().isCreated());
    admin
        .create(AdminProductClient.upsert(cat, sku, slug, "ACTIVE", 5))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE sku = ?", Integer.class, sku))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory i JOIN products p ON p.id = i.product_id"
                    + " WHERE p.sku = ?",
                Integer.class,
                sku))
        .isEqualTo(1);
  }

  @Test
  void tc13CrashAfterProductUpdateRollsBackAndSkipsCacheEviction() throws Exception {
    String sfx = CatalogFixtures.rnd();
    String sku = "SKU-UPD-" + sfx;
    long id = admin.createOk(cat, sku, "upd-" + sfx, 5);
    // Creation publishes a change event for the new slug (F-3); only the update matters below.
    clearInvocations(evictionListener);

    String nameBefore =
        jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, id);
    String slugBefore =
        jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, id);

    // The update has already run and the event published; the fault comes in the response
    // assembly (loadDetail calls findByProductIds) afterwards.
    doThrow(new IllegalStateException("simulated crash after product update"))
        .when(inventoryService)
        .findByProductIds(anyCollection());
    admin
        .update(id, AdminProductClient.upsert(cat, sku, "renamed-" + sfx, "ACTIVE", null))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    assertThat(jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, id))
        .isEqualTo(nameBefore);
    assertThat(jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, id))
        .isEqualTo(slugBefore);
    verify(evictionListener, never()).onProductChanged(any());

    reset(inventoryService); // fault removed
    admin
        .update(id, AdminProductClient.upsert(cat, sku, "renamed-" + sfx, "ACTIVE", null))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, id))
        .isEqualTo("renamed-" + sfx);
    // Positive control: the listener runs exactly once, after the successful commit.
    verify(evictionListener, times(1)).onProductChanged(any());
  }
}
