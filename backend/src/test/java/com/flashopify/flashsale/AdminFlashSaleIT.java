package com.flashopify.flashsale;

import static com.flashopify.support.AdminFlashSaleClient.campaign;
import static com.flashopify.support.AdminFlashSaleClient.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.AdminFlashSaleClient;
import com.flashopify.support.AdminProductClient;
import com.flashopify.support.CatalogFixtures;
import com.flashopify.support.FlashSaleFixtures;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** FLS-015 admin campaign API against real MySQL: validation, reservations, edit and cancel. */
class AdminFlashSaleIT extends AbstractIntegrationTest {

  private static final Duration TOMORROW = Duration.ofDays(1);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  private CatalogFixtures fx;
  private AdminFlashSaleClient admin;
  private AdminProductClient products;
  private long cat;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
    FlashSaleFixtures flash = new FlashSaleFixtures(jdbc);
    admin = new AdminFlashSaleClient(mvc, mapper, flash.adminUser());
    products = new AdminProductClient(mvc, mapper);
    cat = fx.category("Fs " + CatalogFixtures.rnd(), null);
  }

  private long product(int quantity, int reserved) {
    long pid = fx.product(cat, "ACTIVE");
    fx.inventory(pid, quantity, reserved);
    return pid;
  }

  private int reserved(long pid) {
    return jdbc.queryForObject(
        "SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, pid);
  }

  private int campaignsNamed(String name) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM flash_sale_campaigns WHERE name = ?", Integer.class, name);
  }

  private long createOk(Map<String, Object> body) throws Exception {
    return admin.parse(admin.create(body).andExpect(status().isCreated())).get("id").asLong();
  }

  @Test
  void tc1CreateReservesStockAndReturnsDetail() throws Exception {
    long a = product(100, 0);
    long b = product(50, 10);

    JsonNode body =
        admin.parse(
            admin
                .create(campaign("BF", TOMORROW, List.of(item(b, 40), item(a, 30))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.totalStock").value(70))
                .andExpect(jsonPath("$.items[0].productId").value(Math.min(a, b)))
                .andExpect(jsonPath("$.items[0].salePrice").value("9.99"))
                .andExpect(jsonPath("$.items[0].originalPrice").value("10.00"))
                .andExpect(jsonPath("$.items[0].sold").value(0)));

    assertThat(reserved(a)).isEqualTo(30);
    assertThat(reserved(b)).isEqualTo(50); // 10 already reserved + 40, exactly the quantity
    long id = body.get("id").asLong();
    admin.get(id).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
  }

  @Test
  void tc2PastStartAndEndNotAfterStartAre400WithFieldErrors() throws Exception {
    long a = product(10, 0);
    String name = "past-" + CatalogFixtures.rnd();
    Map<String, Object> body = campaign(name, Duration.ofHours(-1), List.of(item(a, 1)));
    body.put("endTime", body.get("startTime"));

    admin
        .create(body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("startTime"))
        .andExpect(jsonPath("$.fieldErrors[1].field").value("endTime"));
    assertThat(campaignsNamed(name)).isZero();
    assertThat(reserved(a)).isZero();
  }

  @Test
  void tc3BeanValidationErrorsAre400WithFieldErrors() throws Exception {
    long a = product(10, 0);
    Map<String, Object> bad = item(a, 0);
    bad.put("salePrice", "-1.00");

    admin
        .create(campaign("", TOMORROW, List.of(bad)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.length()").value(3));
    admin
        .create(campaign("dup", TOMORROW, List.of(item(a, 1), item(a, 2))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("items[1].productId"));
  }

  @Test
  void tc4OverStockIs409AndTheWholeCampaignIsRolledBack() throws Exception {
    long a = product(100, 0);
    long b = product(50, 10); // 40 available
    String name = "over-" + CatalogFixtures.rnd();

    admin
        .create(campaign(name, TOMORROW, List.of(item(a, 30), item(b, 41))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

    assertThat(campaignsNamed(name)).isZero();
    assertThat(reserved(a)).isZero(); // the reservation of a was rolled back
    assertThat(reserved(b)).isEqualTo(10);
  }

  @Test
  void tc5InactiveOrUnknownProductsAreRejected() throws Exception {
    long inactive = fx.product(cat, "INACTIVE");
    fx.inventory(inactive, 10, 0);

    admin
        .create(campaign("inactive", TOMORROW, List.of(item(inactive, 1))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"));
    admin
        .create(campaign("unknown", TOMORROW, List.of(item(Long.MAX_VALUE, 1))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  void tc6UpdateBeforeWarmUpAdjustsReservationsAndKeepsItemIds() throws Exception {
    long a = product(100, 0);
    long b = product(100, 0);
    long c = product(100, 0);
    long id = createOk(campaign("upd", TOMORROW, List.of(item(a, 10), item(b, 20))));
    long itemB =
        jdbc.queryForObject(
            "SELECT id FROM flash_sale_items WHERE campaign_id = ? AND product_id = ?",
            Long.class,
            id,
            b);

    admin
        .update(id, campaign("upd 2", Duration.ofDays(2), List.of(item(b, 5), item(c, 7))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("upd 2"))
        .andExpect(jsonPath("$.totalStock").value(12));

    assertThat(reserved(a)).isZero();
    assertThat(reserved(b)).isEqualTo(5);
    assertThat(reserved(c)).isEqualTo(7);
    assertThat(
            jdbc.queryForObject(
                "SELECT id FROM flash_sale_items WHERE campaign_id = ? AND product_id = ?",
                Long.class,
                id,
                b))
        .isEqualTo(itemB);
  }

  @Test
  void tc7UpdateAfterWarmUpOrWhenRunningIs409AndChangesNothing() throws Exception {
    long a = product(100, 0);
    long id = createOk(campaign("warm", TOMORROW, List.of(item(a, 10))));
    jdbc.update(
        "UPDATE flash_sale_campaigns SET start_time = UTC_TIMESTAMP() + INTERVAL 2 MINUTE,"
            + " end_time = UTC_TIMESTAMP() + INTERVAL 1 HOUR WHERE id = ?",
        id);

    admin
        .update(id, campaign("warm 2", TOMORROW, List.of(item(a, 50))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_EDITABLE"));
    assertThat(reserved(a)).isEqualTo(10);

    jdbc.update("UPDATE flash_sale_campaigns SET status = 'RUNNING' WHERE id = ?", id);
    admin
        .update(id, campaign("warm 3", TOMORROW, List.of(item(a, 50))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_EDITABLE"));
  }

  @Test
  void tc8CancelReleasesUnsoldStockAndIsNotRepeatable() throws Exception {
    long a = product(100, 0);
    long b = product(100, 0);
    long id = createOk(campaign("cancel", TOMORROW, List.of(item(a, 10), item(b, 20))));
    // Simulate a RUNNING campaign whose end-of-campaign sync already recorded 4 sold units of a.
    jdbc.update("UPDATE flash_sale_campaigns SET status = 'RUNNING' WHERE id = ?", id);
    jdbc.update(
        "UPDATE flash_sale_items SET sold = 4 WHERE campaign_id = ? AND product_id = ?", id, a);

    admin
        .cancel(id)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.items").doesNotExist());

    assertThat(reserved(a)).isEqualTo(4); // sold units stay reserved for their orders
    assertThat(reserved(b)).isZero();
    admin
        .cancel(id)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_EDITABLE"));
    assertThat(reserved(a)).isEqualTo(4);
  }

  @Test
  void tc9UnknownCampaignIs404() throws Exception {
    admin.get(Long.MAX_VALUE).andExpect(status().isNotFound());
    admin
        .cancel(Long.MAX_VALUE)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_FOUND"));
  }

  @Test
  void tc10ProductInCampaignCannotBeDeletedUntilTheCampaignIsCancelled() throws Exception {
    long a = product(100, 0);
    long id = createOk(campaign("guard", TOMORROW, List.of(item(a, 10))));

    products
        .delete(a)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRODUCT_IN_ACTIVE_CAMPAIGN"));
    admin.cancel(id).andExpect(status().isOk());
    products.delete(a).andExpect(status().isNoContent());
  }

  @Test
  void tc11ListFiltersByStatusAndPages() throws Exception {
    long a = product(100, 0);
    long scheduled = createOk(campaign("list", Duration.ofDays(3650), List.of(item(a, 1))));
    long cancelled = createOk(campaign("list", Duration.ofDays(3651), List.of(item(a, 1))));
    admin.cancel(cancelled).andExpect(status().isOk());

    admin
        .list("?status=SCHEDULED&sort=startTime,desc&size=1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(1))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(scheduled))
        .andExpect(jsonPath("$.items[0].itemCount").value(1))
        .andExpect(jsonPath("$.items[0].totalStock").value(1));
    admin.list("?sort=name,asc").andExpect(status().isBadRequest());
    admin.list("?status=BOGUS").andExpect(status().isBadRequest());
  }

  @Test
  void tc12CustomerIsForbidden() throws Exception {
    mvc.perform(
            post(AdminFlashSaleClient.BASE)
                .with(AdminProductClient.customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }
}
