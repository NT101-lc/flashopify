package com.flashopify.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.flashopify.inventory.service.InventoryService;
import com.flashopify.product.dto.ProductImportCommand;
import com.flashopify.product.service.ProductImportService;
import com.flashopify.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** TC-6: a failure while creating the inventory rolls the whole product import back. */
class ProductImportAtomicityIT extends AbstractIntegrationTest {

  @Autowired ProductImportService importService;
  @Autowired JdbcTemplate jdbc;

  @MockitoSpyBean InventoryService inventoryService;

  @AfterEach
  void resetSpy() {
    reset(inventoryService);
  }

  private ProductImportCommand command(int sourceId, long categoryId, String slug, String sku) {
    return new ProductImportCommand(
        sourceId,
        categoryId,
        sku,
        "Name " + slug,
        slug,
        "d",
        "b",
        new BigDecimal("2.50"),
        new BigDecimal("4.00"),
        5,
        "http://m/t.png",
        List.of("http://m/1.png", "http://m/2.png"));
  }

  private long category() {
    return importService.upsertCategory("atomic-" + UUID.randomUUID(), "Atomic");
  }

  private long rows(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  @Test
  void inventoryFailureLeavesNoProductAndNoImages() {
    long cat = category();
    int sourceId = 800_001;
    doThrow(new IllegalStateException("inventory down"))
        .when(inventoryService)
        .initialize(anyLong(), anyInt());

    assertThatThrownBy(
            () ->
                importService.importProduct(
                    command(sourceId, cat, "atomic-" + UUID.randomUUID(), "AT-" + sourceId)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(rows("SELECT COUNT(*) FROM products WHERE source_id = ?", sourceId)).isZero();
    assertThat(
            rows(
                "SELECT COUNT(*) FROM product_images WHERE url IN ('http://m/1.png',"
                    + " 'http://m/2.png') AND product_id NOT IN (SELECT id FROM products)"))
        .isZero();
    assertThat(importService.existsBySourceId(sourceId)).isFalse();
  }

  @Test
  void successfulImportWritesProductImagesAndInventoryTogether() {
    long cat = category();
    int sourceId = 800_002;

    Optional<Long> id =
        importService.importProduct(
            command(sourceId, cat, "ok-" + UUID.randomUUID(), "OK-" + sourceId));

    assertThat(id).isPresent();
    assertThat(rows("SELECT COUNT(*) FROM product_images WHERE product_id = ?", id.get()))
        .isEqualTo(2);
    assertThat(rows("SELECT quantity FROM inventory WHERE product_id = ?", id.get())).isEqualTo(5);
    assertThat(importService.existsBySourceId(sourceId)).isTrue();
    // Same source id again: skipped, nothing duplicated.
    assertThat(
            importService.importProduct(
                command(sourceId, cat, "other-" + UUID.randomUUID(), "OK2-" + sourceId)))
        .isEmpty();
    assertThat(rows("SELECT COUNT(*) FROM products WHERE source_id = ?", sourceId)).isEqualTo(1);
  }

  @Test
  void slugAndSkuCollisionsAreMadeUniqueInsideTheSameTransaction() {
    long cat = category();
    String slug = "dup-" + UUID.randomUUID();
    importService.importProduct(command(800_003, cat, slug, "DUP-A"));

    Optional<Long> second = importService.importProduct(command(800_004, cat, slug, "DUP-B"));
    Optional<Long> third = importService.importProduct(command(800_005, cat, "x-" + slug, "DUP-A"));

    assertThat(second).isPresent();
    assertThat(
            jdbc.queryForObject(
                "SELECT slug FROM products WHERE id = ?", String.class, second.get()))
        .isEqualTo(slug + "-800004");
    assertThat(third).isPresent();
    assertThat(
            jdbc.queryForObject("SELECT sku FROM products WHERE id = ?", String.class, third.get()))
        .isEqualTo("DJ-800005");
    assertThat(rows("SELECT COUNT(*) FROM inventory WHERE product_id = ?", second.get()))
        .isEqualTo(1);
  }
}
