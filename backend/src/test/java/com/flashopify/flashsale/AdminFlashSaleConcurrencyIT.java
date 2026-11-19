package com.flashopify.flashsale;

import static com.flashopify.support.AdminFlashSaleClient.campaign;
import static com.flashopify.support.AdminFlashSaleClient.item;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.AdminFlashSaleClient;
import com.flashopify.support.AdminProductClient;
import com.flashopify.support.AdminProductClient.Outcome;
import com.flashopify.support.CatalogFixtures;
import com.flashopify.support.FlashSaleFixtures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** FLS-015 concurrency: real MySQL, real thread pool, threads released together. */
class AdminFlashSaleConcurrencyIT extends AbstractIntegrationTest {

  private static final int TIMEOUT_SECONDS = 60;
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
    admin = new AdminFlashSaleClient(mvc, mapper, new FlashSaleFixtures(jdbc).adminUser());
    products = new AdminProductClient(mvc, mapper);
    cat = fx.category("FsConc " + CatalogFixtures.rnd(), null);
  }

  private long product(int quantity) {
    long pid = fx.product(cat, "ACTIVE");
    fx.inventory(pid, quantity, 0);
    return pid;
  }

  private int reserved(long pid) {
    return jdbc.queryForObject(
        "SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, pid);
  }

  private int reservedByActiveCampaigns(long pid) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(i.total_stock - i.sold), 0) FROM flash_sale_items i"
            + " JOIN flash_sale_campaigns c ON c.id = i.campaign_id"
            + " WHERE i.product_id = ? AND c.status IN ('SCHEDULED', 'RUNNING')",
        Integer.class,
        pid);
  }

  /** Runs every task at the same moment and returns their outcomes in submission order. */
  private List<Outcome> runTogether(List<Callable<Outcome>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    try {
      CountDownLatch ready = new CountDownLatch(tasks.size());
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Outcome>> futures = new ArrayList<>();
      for (Callable<Outcome> task : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return task.call();
                }));
      }
      ready.await();
      go.countDown();
      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> f : futures) {
        outcomes.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void tc13ConcurrentCreatesNeverReserveMoreThanTheQuantity() throws Exception {
    long a = product(100);
    int threads = 10; // each wants 30 of 100: exactly 3 can win
    List<Callable<Outcome>> tasks = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      tasks.add(
          () -> admin.outcome(admin.create(campaign("race", TOMORROW, List.of(item(a, 30))))));
    }

    List<Outcome> outcomes = runTogether(tasks);

    assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(3);
    assertThat(outcomes)
        .filteredOn(o -> o.status() != 201)
        .allSatisfy(
            o -> assertThat(o.code()).isIn("INSUFFICIENT_STOCK", "CONCURRENT_MODIFICATION"));
    assertThat(reserved(a)).isEqualTo(90).isEqualTo(reservedByActiveCampaigns(a));
  }

  @Test
  void tc14ConcurrentCreateAndProductDeleteLeaveAConsistentState() throws Exception {
    for (int round = 0; round < 10; round++) {
      long a = product(100);
      List<Callable<Outcome>> tasks =
          List.of(
              () -> admin.outcome(admin.create(campaign("vs-del", TOMORROW, List.of(item(a, 5))))),
              () -> products.outcome(products.delete(a)));

      List<Outcome> outcomes = runTogether(tasks);

      assertThat(outcomes).noneMatch(o -> o.status() >= 500);
      String productStatus =
          jdbc.queryForObject("SELECT status FROM products WHERE id = ?", String.class, a);
      if (outcomes.get(0).status() == 201) {
        // Campaign won: the delete must have been rejected and the product stays ACTIVE.
        assertThat(outcomes.get(1).code()).isEqualTo("PRODUCT_IN_ACTIVE_CAMPAIGN");
        assertThat(productStatus).isEqualTo("ACTIVE");
        assertThat(reserved(a)).isEqualTo(5);
      } else {
        // Delete won: no campaign may reference the deleted product.
        assertThat(outcomes.get(0).code()).isIn("PRODUCT_NOT_AVAILABLE", "CONCURRENT_MODIFICATION");
        assertThat(reservedByActiveCampaigns(a)).isZero();
        assertThat(reserved(a)).isZero();
      }
    }
  }

  @Test
  void tc15ConcurrentCancelsReleaseStockExactlyOnce() throws Exception {
    long a = product(100);
    long id =
        admin
            .parse(admin.create(campaign("cc", TOMORROW, List.of(item(a, 40)))))
            .get("id")
            .asLong();
    List<Callable<Outcome>> tasks = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      tasks.add(() -> admin.outcome(admin.cancel(id)));
    }

    List<Outcome> outcomes = runTogether(tasks);

    assertThat(outcomes).filteredOn(o -> o.status() == 200).hasSize(1);
    assertThat(outcomes)
        .filteredOn(o -> o.status() != 200)
        .allSatisfy(o -> assertThat(o.code()).isEqualTo("CAMPAIGN_NOT_EDITABLE"));
    assertThat(reserved(a)).isZero();
  }

  @Test
  void tc16ConcurrentUpdateAndInventoryChangeKeepReservedWithinQuantity() throws Exception {
    long a = product(100);
    long id =
        admin
            .parse(admin.create(campaign("ui", TOMORROW, List.of(item(a, 10)))))
            .get("id")
            .asLong();
    int version =
        jdbc.queryForObject("SELECT version FROM inventory WHERE product_id = ?", Integer.class, a);

    // Raise the campaign to 80 while the admin lowers the quantity to 50: at most one may win.
    List<Outcome> outcomes =
        runTogether(
            List.of(
                () ->
                    admin.outcome(admin.update(id, campaign("ui", TOMORROW, List.of(item(a, 80))))),
                () -> products.outcome(products.putInventory(a, 50, version))));

    assertThat(outcomes).noneMatch(o -> o.status() >= 500);
    int[] row =
        jdbc.queryForObject(
            "SELECT quantity, reserved FROM inventory WHERE product_id = ?",
            (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)},
            a);
    assertThat(row[1]).isLessThanOrEqualTo(row[0]);
    assertThat(row[1]).isEqualTo(reservedByActiveCampaigns(a));
  }
}
