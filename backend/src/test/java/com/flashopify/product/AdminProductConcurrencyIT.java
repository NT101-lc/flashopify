package com.flashopify.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.AdminProductClient;
import com.flashopify.support.AdminProductClient.Outcome;
import com.flashopify.support.CatalogFixtures;
import com.flashopify.support.FlashSaleFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** FLS-009 concurrency: real MySQL, real thread pool, threads released together. */
class AdminProductConcurrencyIT extends AbstractIntegrationTest {

  private static final int TIMEOUT_SECONDS = 60;

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  private CatalogFixtures fx;
  private FlashSaleFixtures flash;
  private AdminProductClient admin;
  private long cat;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
    flash = new FlashSaleFixtures(jdbc);
    admin = new AdminProductClient(mvc, mapper);
    cat = fx.category("Conc " + CatalogFixtures.rnd(), null);
  }

  @Test
  void tc9TwentyConcurrentInventoryUpdatesWithSameVersionLeaveOneWinner() throws Exception {
    long pid = fx.product(cat, "ACTIVE");
    fx.inventory(pid, 100, 0);
    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch ready = new CountDownLatch(threads);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Outcome>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        int quantity = 200 + i; // distinct per thread, so the winner is identifiable
        Callable<Outcome> task =
            () -> {
              ready.countDown();
              go.await();
              return admin.outcome(admin.putInventory(pid, quantity, 0));
            };
        futures.add(pool.submit(task));
      }
      ready.await();
      go.countDown();

      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> f : futures) {
        outcomes.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }

      long winners = outcomes.stream().filter(o -> o.status() == 200).count();
      long conflicts =
          outcomes.stream()
              .filter(o -> o.status() == 409 && "CONCURRENT_MODIFICATION".equals(o.code()))
              .count();
      assertThat(winners).isEqualTo(1);
      assertThat(conflicts).isEqualTo(19);
      assertThat(outcomes.stream().map(Outcome::status)).doesNotContain(500);

      Outcome winner = outcomes.stream().filter(o -> o.status() == 200).findFirst().orElseThrow();
      assertThat(winner.body().get("version").asInt()).isEqualTo(1);
      int dbQuantity =
          jdbc.queryForObject(
              "SELECT quantity FROM inventory WHERE product_id = ?", Integer.class, pid);
      int dbVersion =
          jdbc.queryForObject(
              "SELECT version FROM inventory WHERE product_id = ?", Integer.class, pid);
      assertThat(dbQuantity).isEqualTo(winner.body().get("quantity").asInt());
      assertThat(dbVersion).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void tc10ReservationRaceNeverBreaksStockInvariantAndUpdatesFailCleanly() throws Exception {
    long pid = fx.product(cat, "ACTIVE");
    fx.inventory(pid, 100, 0);
    int iterations = 200;
    int reservationsSucceeded = 0;
    int writerOk = 0;
    int writerConflict = 0;
    int serverErrors = 0;
    Map<String, Integer> histogram = new TreeMap<>();
    // Seeded jitter before the reservation: without it the writer's first read always wins the
    // start race and the writer never sees a fresh version. Both orderings must occur.
    Random jitter = new Random(20260628L);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int iteration = 0; iteration < iterations; iteration++) {
        // Known state every iteration; the version keeps growing, which is fine.
        jdbc.update("UPDATE inventory SET quantity = 100, reserved = 0 WHERE product_id = ?", pid);
        CyclicBarrier start = new CyclicBarrier(2);
        long reserveDelayNanos = jitter.nextInt(2_000_000);
        // Thread A: a guarded reservation, as the flash-sale path will do it.
        Callable<Integer> reserve =
            () -> {
              start.await();
              LockSupport.parkNanos(reserveDelayNanos);
              return jdbc.update(
                  "UPDATE inventory SET reserved = reserved + 10, version = version + 1"
                      + " WHERE product_id = ? AND reserved + 10 <= quantity",
                  pid);
            };
        // Thread B: reads version and reserved, then lowers quantity to reserved + 5.
        Callable<Outcome> writer =
            () -> {
              start.await();
              Map<String, Object> seen =
                  jdbc.queryForMap(
                      "SELECT version, reserved FROM inventory WHERE product_id = ?", pid);
              int version = ((Number) seen.get("version")).intValue();
              int reservedSeen = ((Number) seen.get("reserved")).intValue();
              return admin.outcome(admin.putInventory(pid, reservedSeen + 5, version));
            };
        Future<Integer> reserveFuture = pool.submit(reserve);
        Future<Outcome> writerFuture = pool.submit(writer);

        int reserveRows = reserveFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // exceptions fail
        Outcome outcome = writerFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (reserveRows == 1) {
          reservationsSucceeded++;
        }
        if (outcome.status() == 500) {
          serverErrors++;
        }
        histogram.merge(
            "writer " + outcome.status() + "/" + outcome.code() + " reserve=" + reserveRows,
            1,
            Integer::sum);
        if (outcome.status() == 200) {
          writerOk++;
        } else if (outcome.status() == 409
            && ("CONCURRENT_MODIFICATION".equals(outcome.code())
                || "INSUFFICIENT_STOCK".equals(outcome.code()))) {
          writerConflict++;
        }
        boolean clean =
            outcome.status() == 200
                || (outcome.status() == 409
                    && ("CONCURRENT_MODIFICATION".equals(outcome.code())
                        || "INSUFFICIENT_STOCK".equals(outcome.code())));
        assertThat(clean).as("iteration %d: unexpected outcome %s", iteration, outcome).isTrue();

        Map<String, Object> row =
            jdbc.queryForMap("SELECT quantity, reserved FROM inventory WHERE product_id = ?", pid);
        int quantity = ((Number) row.get("quantity")).intValue();
        int reserved = ((Number) row.get("reserved")).intValue();
        assertThat(reserved)
            .as("iteration %d: 0 <= reserved <= quantity", iteration)
            .isBetween(0, quantity);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(serverErrors).as("no 500 responses").isZero();
    assertThat(reservationsSucceeded)
        .as("reservation succeeded in at least 25 percent of iterations")
        .isGreaterThanOrEqualTo(iterations / 4);
    assertThat(writerOk)
        .as("writer got at least one 200; histogram %s", histogram)
        .isGreaterThanOrEqualTo(1);
    assertThat(writerConflict)
        .as(
            "writer got at least one 409 CONCURRENT_MODIFICATION or INSUFFICIENT_STOCK; histogram %s",
            histogram)
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void tc11TenConcurrentCreatesOfSameSkuYieldOneWinnerAndNineDuplicates() throws Exception {
    String sku = "SKU-RACE-" + CatalogFixtures.rnd();
    String sfx = CatalogFixtures.rnd();
    int threads = 10;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch ready = new CountDownLatch(threads);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Outcome>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        String slug = "race-" + sfx + "-" + i; // different slugs, same SKU
        Callable<Outcome> task =
            () -> {
              ready.countDown();
              go.await();
              return admin.outcome(
                  admin.create(AdminProductClient.upsert(cat, sku, slug, "ACTIVE", 3)));
            };
        futures.add(pool.submit(task));
      }
      ready.await();
      go.countDown();

      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> f : futures) {
        outcomes.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }

      long created = outcomes.stream().filter(o -> o.status() == 201).count();
      long duplicates =
          outcomes.stream()
              .filter(o -> o.status() == 409 && "DUPLICATE_SKU".equals(o.code()))
              .count();
      assertThat(created).isEqualTo(1);
      assertThat(duplicates).isEqualTo(9);
      assertThat(outcomes.stream().map(Outcome::status)).doesNotContain(500);

      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM products WHERE sku = ?", Integer.class, sku))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM inventory i JOIN products p ON p.id = i.product_id"
                      + " WHERE p.sku = ?",
                  Integer.class,
                  sku))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Lock order: admin updateInventory takes FOR SHARE on products, then the inventory row; the
   * simulated FLS-015 reservation takes the same locks in the same order. Neither side may see a
   * deadlock or lock-wait failure, and the admin side must never answer 500.
   */
  @Test
  void tc19LockOrderAdminUpdatesAndReservationsNeverDeadlock() throws Exception {
    long pid = fx.product(cat, "ACTIVE");
    fx.inventory(pid, 60, 0);
    long owner = flash.adminUser();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    int iterations = 50;
    int reservationsSucceeded = 0;
    int serverErrors = 0;
    List<Throwable> simulatedFailures = new ArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < iterations; i++) {
        // One campaign per iteration, so each reservation's flash-sale item is unique.
        long campaign = flash.campaign(owner, "SCHEDULED");
        int quantity = 40 + (i % 40);
        CyclicBarrier start = new CyclicBarrier(2);

        Callable<Boolean> reservation =
            () -> {
              start.await();
              return tx.execute(
                  status -> {
                    jdbc.queryForObject(
                        "SELECT id FROM products WHERE id = ? FOR SHARE", Long.class, pid);
                    int rows =
                        jdbc.update(
                            "UPDATE inventory SET reserved = reserved + 1, version = version + 1"
                                + " WHERE product_id = ? AND reserved + 1 <= quantity",
                            pid);
                    if (rows == 1) {
                      jdbc.update(
                          "INSERT INTO flash_sale_items (campaign_id, product_id, sale_price,"
                              + " total_stock) VALUES (?, ?, 1.00, 1)",
                          campaign,
                          pid);
                    }
                    return rows == 1;
                  });
            };
        Callable<Outcome> adminUpdate =
            () -> {
              start.await();
              int version =
                  jdbc.queryForObject(
                      "SELECT version FROM inventory WHERE product_id = ?", Integer.class, pid);
              return admin.outcome(admin.putInventory(pid, quantity, version));
            };

        Future<Boolean> reservationFuture = pool.submit(reservation);
        Future<Outcome> adminFuture = pool.submit(adminUpdate);

        boolean reserved = false;
        try {
          reserved = Boolean.TRUE.equals(reservationFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
          simulatedFailures.add(e.getCause());
        }
        Outcome outcome = adminFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (reserved) {
          reservationsSucceeded++;
        }
        if (outcome.status() == 500) {
          serverErrors++;
        }

        Map<String, Object> row =
            jdbc.queryForMap("SELECT quantity, reserved FROM inventory WHERE product_id = ?", pid);
        int stock = ((Number) row.get("quantity")).intValue();
        int reservedNow = ((Number) row.get("reserved")).intValue();
        assertThat(reservedNow)
            .as("iteration %d: 0 <= reserved <= quantity", i)
            .isBetween(0, stock);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(simulatedFailures)
        .as("simulated FLS-015 transactions must not deadlock or time out on locks")
        .isEmpty();
    assertThat(serverErrors).as("no 500 from the admin side").isZero();
    // Nothing releases reservations in this test, so every success is still reserved.
    int reservedNow =
        jdbc.queryForObject(
            "SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, pid);
    assertThat(reservedNow).isEqualTo(reservationsSucceeded);
    int items =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flash_sale_items WHERE product_id = ?", Integer.class, pid);
    assertThat(items).isEqualTo(reservationsSucceeded);
  }

  /**
   * Variant of TC-19 without the explicit FOR SHARE: the reservation only updates the inventory row
   * and inserts the flash-sale item, whose foreign key takes a shared lock on the product
   * implicitly.
   */
  @Test
  void tc19bImplicitForeignKeyLockNeverDeadlocksWithAdminUpdates() throws Exception {
    long pid = fx.product(cat, "ACTIVE");
    fx.inventory(pid, 60, 0);
    long owner = flash.adminUser();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    int iterations = 50;
    int reservationsSucceeded = 0;
    int serverErrors = 0;
    List<Throwable> simulatedFailures = new ArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < iterations; i++) {
        long campaign = flash.campaign(owner, "SCHEDULED");
        int quantity = 40 + (i % 40);
        CyclicBarrier start = new CyclicBarrier(2);

        Callable<Boolean> reservation =
            () -> {
              start.await();
              return tx.execute(
                  status -> {
                    int rows =
                        jdbc.update(
                            "UPDATE inventory SET reserved = reserved + 1, version = version + 1"
                                + " WHERE product_id = ? AND reserved + 1 <= quantity",
                            pid);
                    if (rows == 1) {
                      jdbc.update(
                          "INSERT INTO flash_sale_items (campaign_id, product_id, sale_price,"
                              + " total_stock) VALUES (?, ?, 1.00, 1)",
                          campaign,
                          pid);
                    }
                    return rows == 1;
                  });
            };
        Callable<Outcome> adminUpdate =
            () -> {
              start.await();
              int version =
                  jdbc.queryForObject(
                      "SELECT version FROM inventory WHERE product_id = ?", Integer.class, pid);
              return admin.outcome(admin.putInventory(pid, quantity, version));
            };

        Future<Boolean> reservationFuture = pool.submit(reservation);
        Future<Outcome> adminFuture = pool.submit(adminUpdate);

        boolean reserved = false;
        try {
          reserved = Boolean.TRUE.equals(reservationFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
          simulatedFailures.add(e.getCause());
        }
        Outcome outcome = adminFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (reserved) {
          reservationsSucceeded++;
        }
        if (outcome.status() == 500) {
          serverErrors++;
        }

        Map<String, Object> row =
            jdbc.queryForMap("SELECT quantity, reserved FROM inventory WHERE product_id = ?", pid);
        int stock = ((Number) row.get("quantity")).intValue();
        int reservedNow = ((Number) row.get("reserved")).intValue();
        assertThat(reservedNow)
            .as("iteration %d: 0 <= reserved <= quantity", i)
            .isBetween(0, stock);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(simulatedFailures)
        .as("simulated FLS-015 transactions must not deadlock or time out on locks")
        .isEmpty();
    assertThat(serverErrors).as("no 500 from the admin side").isZero();
    int reservedNow =
        jdbc.queryForObject(
            "SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, pid);
    assertThat(reservedNow).isEqualTo(reservationsSucceeded);
    int items =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flash_sale_items WHERE product_id = ?", Integer.class, pid);
    assertThat(items).isEqualTo(reservationsSucceeded);
  }
}
