package com.flashopify.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.AdminProductClient;
import com.flashopify.support.CatalogFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** FLS-009 inventory update: version-guarded quantity changes that never drop below reserved. */
class AdminInventoryIT extends AbstractIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  private CatalogFixtures fx;
  private AdminProductClient admin;
  private long cat;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
    admin = new AdminProductClient(mvc, mapper);
    cat = fx.category("Inv " + CatalogFixtures.rnd(), null);
  }

  /** Product with quantity 100, reserved 30 and version 3. */
  private long seededProduct() {
    long pid = fx.product(cat, "ACTIVE");
    fx.inventory(pid, 100, 30);
    jdbc.update("UPDATE inventory SET version = 3 WHERE product_id = ?", pid);
    return pid;
  }

  private int[] row(long pid) {
    return jdbc.queryForObject(
        "SELECT quantity, reserved, version FROM inventory WHERE product_id = ?",
        (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)},
        pid);
  }

  @Test
  void tc6QuantityUpdateKeepsReservedAndBumpsVersion() throws Exception {
    long pid = seededProduct();

    admin
        .putInventory(pid, 250, 3)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quantity").value(250))
        .andExpect(jsonPath("$.reserved").value(30))
        .andExpect(jsonPath("$.available").value(220))
        .andExpect(jsonPath("$.version").value(4));
    assertThat(row(pid)).containsExactly(250, 30, 4);

    // Quantity equal to reserved is allowed.
    admin
        .putInventory(pid, 30, 4)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quantity").value(30))
        .andExpect(jsonPath("$.available").value(0))
        .andExpect(jsonPath("$.version").value(5));
    assertThat(row(pid)).containsExactly(30, 30, 5);
  }

  @Test
  void tc7StaleVersionIs409AndRowUnchanged() throws Exception {
    long pid = seededProduct();

    admin
        .putInventory(pid, 50, 2)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    assertThat(row(pid)).containsExactly(100, 30, 3);
  }

  @Test
  void tc8QuantityBelowReservedIs409InsufficientStockAndRowUnchanged() throws Exception {
    long pid = seededProduct();

    admin
        .putInventory(pid, 29, 3)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    assertThat(row(pid)).containsExactly(100, 30, 3);
  }
}
