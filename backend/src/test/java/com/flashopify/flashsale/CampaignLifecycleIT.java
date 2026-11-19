package com.flashopify.flashsale;

import static com.flashopify.support.AdminFlashSaleClient.campaign;
import static com.flashopify.support.AdminFlashSaleClient.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.flashsale.service.CampaignLifecycleService;
import com.flashopify.flashsale.service.FlashSaleKeys;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.AdminFlashSaleClient;
import com.flashopify.support.CatalogFixtures;
import com.flashopify.support.FlashSaleFixtures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** FLS-016 campaign lifecycle against real MySQL and Redis; the scheduler is driven by hand. */
class CampaignLifecycleIT extends AbstractIntegrationTest {

  /** Inside the 5-minute warm-up lead, so the campaign is due for warm-up right away. */
  private static final Duration SOON = Duration.ofMinutes(3);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired CampaignLifecycleService lifecycle;

  private CatalogFixtures fx;
  private AdminFlashSaleClient admin;
  private long cat;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
    admin = new AdminFlashSaleClient(mvc, mapper, new FlashSaleFixtures(jdbc).adminUser());
    cat = fx.category("Life " + CatalogFixtures.rnd(), null);
  }

  private long product(int quantity) {
    long pid = fx.product(cat, "ACTIVE");
    fx.inventory(pid, quantity, 0);
    return pid;
  }

  private long create(Duration startIn, long productId, int totalStock) throws Exception {
    return admin
        .parse(
            admin
                .create(campaign("life", startIn, List.of(item(productId, totalStock))))
                .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private long itemId(long campaignId) {
    return jdbc.queryForObject(
        "SELECT id FROM flash_sale_items WHERE campaign_id = ?", Long.class, campaignId);
  }

  private String campaignStatus(long id) {
    return jdbc.queryForObject(
        "SELECT status FROM flash_sale_campaigns WHERE id = ?", String.class, id);
  }

  private int reserved(long pid) {
    return jdbc.queryForObject(
        "SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, pid);
  }

  private int sold(long itemId) {
    return jdbc.queryForObject(
        "SELECT sold FROM flash_sale_items WHERE id = ?", Integer.class, itemId);
  }

  private String stock(long itemId) {
    return redis.opsForValue().get(FlashSaleKeys.stock(itemId));
  }

  private Map<Object, Object> meta(long itemId) {
    return redis.opsForHash().entries(FlashSaleKeys.meta(itemId));
  }

  /** Moves the window so that the campaign has started (and optionally ended) already. */
  private void shiftWindow(long id, String startExpr, String endExpr) {
    jdbc.update(
        "UPDATE flash_sale_campaigns SET start_time = "
            + startExpr
            + ", end_time = "
            + endExpr
            + " WHERE id = ?",
        id);
  }

  @Test
  void tc1WarmUpLoadsStockWindowAndLimitUnderOneHashTag() throws Exception {
    long pid = product(100);
    long id = create(SOON, pid, 30);
    long itemId = itemId(id);

    lifecycle.warmUpDue();

    assertThat(FlashSaleKeys.all(itemId)).allMatch(k -> k.endsWith("{" + itemId + "}"));
    assertThat(stock(itemId)).isEqualTo("30");
    assertThat(meta(itemId))
        .containsEntry("total", "30")
        .containsEntry("limit", "1")
        .containsEntry("active", "1")
        .containsKeys("start", "end");
    assertThat(redis.getExpire(FlashSaleKeys.stock(itemId))).isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT warmed_up_at IS NOT NULL FROM flash_sale_campaigns WHERE id = ?",
                Boolean.class,
                id))
        .isTrue();
    admin
        .update(id, campaign("life 2", Duration.ofDays(1), List.of(item(pid, 30))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_EDITABLE"));
  }

  @Test
  void tc2WarmUpRerunNeverOverwritesStockThatIsSelling() throws Exception {
    long id = create(SOON, product(100), 30);
    long itemId = itemId(id);
    lifecycle.warmUpDue();
    redis.opsForValue().decrement(FlashSaleKeys.stock(itemId), 12); // 12 units sold
    // Simulate a lost DB commit so the job loads the campaign again.
    jdbc.update("UPDATE flash_sale_campaigns SET warmed_up_at = NULL WHERE id = ?", id);

    lifecycle.warmUpDue();

    assertThat(stock(itemId)).isEqualTo("18");
  }

  @Test
  void tc3StartTurnsAWarmedUpCampaignRunningAtItsStartTime() throws Exception {
    long id = create(SOON, product(100), 30);
    lifecycle.startDue();
    assertThat(campaignStatus(id)).isEqualTo("SCHEDULED"); // not warmed up, not started

    shiftWindow(id, "UTC_TIMESTAMP() - INTERVAL 1 MINUTE", "UTC_TIMESTAMP() + INTERVAL 1 HOUR");
    lifecycle.tick(); // warm-up then start in the same tick

    assertThat(campaignStatus(id)).isEqualTo("RUNNING");
    assertThat(stock(itemId(id))).isEqualTo("30");
  }

  @Test
  void tc4EndSyncsSoldFromRedisReleasesUnsoldAndDeactivates() throws Exception {
    long pid = product(100);
    long id = create(SOON, pid, 10);
    long itemId = itemId(id);
    shiftWindow(id, "UTC_TIMESTAMP() - INTERVAL 1 MINUTE", "UTC_TIMESTAMP() + INTERVAL 1 HOUR");
    lifecycle.tick();
    redis.opsForValue().decrement(FlashSaleKeys.stock(itemId), 3); // 3 sold
    shiftWindow(id, "UTC_TIMESTAMP() - INTERVAL 2 HOUR", "UTC_TIMESTAMP() - INTERVAL 1 SECOND");

    lifecycle.endDue();

    assertThat(campaignStatus(id)).isEqualTo("ENDED");
    assertThat(sold(itemId)).isEqualTo(3);
    assertThat(reserved(pid)).isEqualTo(3); // sold units stay reserved for their orders
    assertThat(meta(itemId)).containsEntry("active", "0");
    lifecycle.endDue(); // a second run changes nothing
    assertThat(reserved(pid)).isEqualTo(3);
  }

  @Test
  void tc5CampaignThatNeverWarmedUpEndsWithNothingSold() throws Exception {
    long pid = product(100);
    long id = create(SOON, pid, 10);
    shiftWindow(id, "UTC_TIMESTAMP() - INTERVAL 2 HOUR", "UTC_TIMESTAMP() - INTERVAL 1 HOUR");

    lifecycle.tick();

    assertThat(campaignStatus(id)).isEqualTo("ENDED");
    assertThat(sold(itemId(id))).isZero();
    assertThat(reserved(pid)).isZero();
    assertThat(stock(itemId(id))).isNull(); // an ended campaign is never warmed up
  }

  @Test
  void tc6LostRedisStockKeepsTheCampaignRunningInsteadOfGuessing() throws Exception {
    long pid = product(100);
    long id = create(SOON, pid, 10);
    shiftWindow(id, "UTC_TIMESTAMP() - INTERVAL 1 MINUTE", "UTC_TIMESTAMP() + INTERVAL 1 HOUR");
    lifecycle.tick();
    redis.delete(FlashSaleKeys.all(itemId(id)));
    shiftWindow(id, "UTC_TIMESTAMP() - INTERVAL 2 HOUR", "UTC_TIMESTAMP() - INTERVAL 1 SECOND");

    lifecycle.endDue();

    assertThat(campaignStatus(id)).isEqualTo("RUNNING");
    assertThat(reserved(pid)).isEqualTo(10);
  }

  @Test
  void tc7CancelOfARunningCampaignUsesTheRedisStockAndStopsSales() throws Exception {
    long pid = product(100);
    long id = create(SOON, pid, 10);
    long itemId = itemId(id);
    shiftWindow(id, "UTC_TIMESTAMP() - INTERVAL 1 MINUTE", "UTC_TIMESTAMP() + INTERVAL 1 HOUR");
    lifecycle.tick();
    redis.opsForValue().decrement(FlashSaleKeys.stock(itemId), 4);

    admin.cancel(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

    assertThat(sold(itemId)).isEqualTo(4);
    assertThat(reserved(pid)).isEqualTo(4);
    assertThat(meta(itemId)).containsEntry("active", "0");
  }

  @Test
  void tc8ConcurrentSchedulersHandleEachCampaignExactlyOnce() throws Exception {
    int campaigns = 5;
    List<long[]> created = new ArrayList<>(); // {campaignId, productId, itemId}
    for (int i = 0; i < campaigns; i++) {
      long pid = product(100);
      long id = create(SOON, pid, 10);
      shiftWindow(id, "UTC_TIMESTAMP() - INTERVAL 1 MINUTE", "UTC_TIMESTAMP() + INTERVAL 1 HOUR");
      created.add(new long[] {id, pid, itemId(id)});
    }
    runConcurrently(8, lifecycle::tick);
    for (long[] c : created) {
      assertThat(campaignStatus(c[0])).isEqualTo("RUNNING");
      redis.opsForValue().decrement(FlashSaleKeys.stock(c[2]), 2);
      shiftWindow(c[0], "UTC_TIMESTAMP() - INTERVAL 2 HOUR", "UTC_TIMESTAMP() - INTERVAL 1 SECOND");
    }

    runConcurrently(8, lifecycle::endDue);

    for (long[] c : created) {
      assertThat(campaignStatus(c[0])).isEqualTo("ENDED");
      assertThat(sold(c[2])).isEqualTo(2);
      assertThat(reserved(c[1])).isEqualTo(2); // released exactly once
    }
  }

  private static void runConcurrently(int threads, Runnable work) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  work.run();
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> f : futures) {
        f.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
