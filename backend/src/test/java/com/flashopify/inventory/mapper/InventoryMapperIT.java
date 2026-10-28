package com.flashopify.inventory.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.CatalogFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class InventoryMapperIT extends AbstractIntegrationTest {

  @Autowired InventoryMapper inventoryMapper;
  @Autowired JdbcTemplate jdbc;

  private CatalogFixtures fx;
  private long cat;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
    cat = fx.category("InvMapper " + CatalogFixtures.rnd(), null);
  }

  /** Product with quantity 100, reserved 30 and version 4. */
  private long seeded() {
    long pid = fx.product(cat, "ACTIVE");
    fx.inventory(pid, 100, 30);
    jdbc.update("UPDATE inventory SET version = 4 WHERE product_id = ?", pid);
    return pid;
  }

  private int[] row(long pid) {
    return jdbc.queryForObject(
        "SELECT quantity, reserved, version FROM inventory WHERE product_id = ?",
        (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)},
        pid);
  }

  @Test
  void tc17UpdateQuantityReturnsOneAndBumpsVersionOnMatch() {
    long pid = seeded();

    assertThat(inventoryMapper.updateQuantity(pid, 250, 4)).isEqualTo(1);
    assertThat(row(pid)).containsExactly(250, 30, 5);
  }

  @Test
  void tc17UpdateQuantityReturnsZeroOnVersionMismatch() {
    long pid = seeded();
    inventoryMapper.updateQuantity(pid, 250, 4); // version now 5

    assertThat(inventoryMapper.updateQuantity(pid, 260, 4)).isZero();
    assertThat(row(pid)).containsExactly(250, 30, 5);
  }

  @Test
  void tc17UpdateQuantityReturnsZeroWhenBelowReserved() {
    long pid = seeded();

    assertThat(inventoryMapper.updateQuantity(pid, 29, 4)).isZero();
    assertThat(row(pid)).containsExactly(100, 30, 4);

    // Equal to reserved is accepted.
    assertThat(inventoryMapper.updateQuantity(pid, 30, 4)).isEqualTo(1);
    assertThat(row(pid)).containsExactly(30, 30, 5);
  }
}
