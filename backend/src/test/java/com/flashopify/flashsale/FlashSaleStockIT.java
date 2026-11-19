package com.flashopify.flashsale;

import static com.flashopify.flashsale.service.FlashSaleStockService.PurchaseResult.LIMIT_EXCEEDED;
import static com.flashopify.flashsale.service.FlashSaleStockService.PurchaseResult.NOT_ACTIVE;
import static com.flashopify.flashsale.service.FlashSaleStockService.PurchaseResult.OK;
import static com.flashopify.flashsale.service.FlashSaleStockService.PurchaseResult.SOLD_OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flashopify.flashsale.domain.Campaign;
import com.flashopify.flashsale.domain.CampaignItem;
import com.flashopify.flashsale.service.FlashSaleKeys;
import com.flashopify.flashsale.service.FlashSaleStockService.PurchaseResult;
import com.flashopify.flashsale.service.FlashSaleStockService.RestoreResult;
import com.flashopify.flashsale.service.FlashSaleStockStore;
import com.flashopify.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * FLS-017 Lua purchase/restore against a real Redis. Items are loaded with the warm-up script only
 * (no MySQL), under random ids so tests never share keys.
 */
class FlashSaleStockIT extends AbstractIntegrationTest {

  private static final int TIMEOUT_SECONDS = 60;
  private static final AtomicLong USERS = new AtomicLong(1_000_000);

  @Autowired FlashSaleStockStore store;
  @Autowired StringRedisTemplate redis;

  /** Loads an item whose window is [now + startIn, now + startIn + 1h). Returns its id. */
  private long load(int totalStock, int limitPerUser, Duration startIn) {
    long itemId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
    Instant start = Instant.now().plus(startIn);
    Campaign campaign =
        Campaign.builder().id(1L).startTime(start).endTime(start.plus(Duration.ofHours(1))).build();
    CampaignItem item =
        CampaignItem.builder()
            .id(itemId)
            .salePrice(BigDecimal.ONE)
            .totalStock(totalStock)
            .limitPerUser(limitPerUser)
            .build();
    store.warmUp(campaign, item, start.plus(Duration.ofDays(1)));
    return itemId;
  }

  /** An item that opened a minute ago. */
  private long open(int totalStock, int limitPerUser) {
    return load(totalStock, limitPerUser, Duration.ofMinutes(-1));
  }

  private int stock(long itemId) {
    return Integer.parseInt(redis.opsForValue().get(FlashSaleKeys.stock(itemId)));
  }

  private int bought(long itemId, long userId) {
    Object v = redis.opsForHash().get(FlashSaleKeys.bought(itemId), String.valueOf(userId));
    return v == null ? 0 : Integer.parseInt((String) v);
  }

  private long sumBought(long itemId) {
    return redis.opsForHash().values(FlashSaleKeys.bought(itemId)).stream()
        .mapToLong(v -> Long.parseLong((String) v))
        .sum();
  }

  private static long user() {
    return USERS.incrementAndGet();
  }

  /** One thread per task, all released together (fewer threads would deadlock on the latch). */
  private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    try {
      CountDownLatch ready = new CountDownLatch(tasks.size());
      CountDownLatch go = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> task : tasks) {
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
      List<T> results = new ArrayList<>();
      for (Future<T> f : futures) {
        results.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static Map<PurchaseResult, Integer> histogram(List<PurchaseResult> results) {
    Map<PurchaseResult, Integer> h = new EnumMap<>(PurchaseResult.class);
    results.forEach(r -> h.merge(r, 1, Integer::sum));
    return h;
  }

  @Test
  void tc01ThousandThreadsForAHundredUnitsGetExactlyAHundredOk() throws Exception {
    long itemId = open(100, 1);
    int threads = 1_000;
    List<Callable<PurchaseResult>> tasks = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      long userId = user();
      tasks.add(() -> store.tryPurchase(itemId, userId, 1));
    }

    Map<PurchaseResult, Integer> h = histogram(runTogether(tasks));

    assertThat(h).containsEntry(OK, 100).containsEntry(SOLD_OUT, 900).hasSize(2);
    assertThat(stock(itemId)).isZero();
    assertThat(sumBought(itemId)).isEqualTo(100);
    assertThat(redis.opsForHash().size(FlashSaleKeys.bought(itemId))).isEqualTo(100);
  }

  @Test
  void tc02FiveConcurrentRequestsOfOneUserWithLimitOneGiveOneOk() throws Exception {
    long itemId = open(10, 1);
    long userId = user();
    List<Callable<PurchaseResult>> tasks = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      tasks.add(() -> store.tryPurchase(itemId, userId, 1));
    }

    Map<PurchaseResult, Integer> h = histogram(runTogether(tasks));

    assertThat(h).containsEntry(OK, 1).containsEntry(LIMIT_EXCEEDED, 4).hasSize(2);
    assertThat(stock(itemId)).isEqualTo(9);
    assertThat(bought(itemId, userId)).isEqualTo(1);
  }

  @Test
  void tc04PurchasesOutsideTheWindowOrOfUnloadedOrClosedItemsAreNotActive() {
    long notStarted = load(10, 1, Duration.ofMinutes(4));
    long ended = load(10, 1, Duration.ofHours(-2)); // window ended an hour ago
    long closed = open(10, 1);
    store.deactivate(closed);

    assertThat(store.tryPurchase(notStarted, user(), 1)).isEqualTo(NOT_ACTIVE);
    assertThat(store.tryPurchase(ended, user(), 1)).isEqualTo(NOT_ACTIVE);
    assertThat(store.tryPurchase(closed, user(), 1)).isEqualTo(NOT_ACTIVE);
    assertThat(store.tryPurchase(Long.MAX_VALUE - 7, user(), 1)).isEqualTo(NOT_ACTIVE);
    assertThat(stock(notStarted)).isEqualTo(10);
    assertThat(stock(closed)).isEqualTo(10);
  }

  @Test
  void checksRunInOrderWindowThenLimitThenStockAndChangeNothingOnFailure() {
    long itemId = open(3, 2);
    long a = user();

    assertThat(store.tryPurchase(itemId, a, 3)).isEqualTo(LIMIT_EXCEEDED); // limit before stock
    assertThat(store.tryPurchase(itemId, a, 2)).isEqualTo(OK);
    assertThat(store.tryPurchase(itemId, a, 1)).isEqualTo(LIMIT_EXCEEDED);
    long b = user();
    assertThat(store.tryPurchase(itemId, b, 2)).isEqualTo(SOLD_OUT); // only 1 left
    assertThat(bought(itemId, b)).isZero();
    assertThat(store.tryPurchase(itemId, b, 1)).isEqualTo(OK);
    assertThat(stock(itemId)).isZero();
    assertThat(redis.getExpire(FlashSaleKeys.bought(itemId))).isPositive();
  }

  @Test
  void restoreGivesUnitsBackAndLetsTheUserBuyAgain() {
    long itemId = open(5, 1);
    long a = user();
    assertThat(store.tryPurchase(itemId, a, 1)).isEqualTo(OK);

    assertThat(store.restore(itemId, a, 1)).isEqualTo(RestoreResult.OK);

    assertThat(stock(itemId)).isEqualTo(5);
    assertThat(bought(itemId, a)).isZero();
    assertThat(store.tryPurchase(itemId, a, 1)).isEqualTo(OK);
  }

  @Test
  void restoreNeverExceedsWhatTheUserHoldsOrTheTotalStock() {
    long itemId = open(5, 3);
    long a = user();
    assertThat(store.restore(itemId, a, 1)).isEqualTo(RestoreResult.NOT_HELD);
    assertThat(store.tryPurchase(itemId, a, 2)).isEqualTo(OK);
    assertThat(store.restore(itemId, a, 3)).isEqualTo(RestoreResult.NOT_HELD);
    // A corrupted state where stock is already full: restoring must not push it over the total.
    redis.opsForValue().set(FlashSaleKeys.stock(itemId), "5");
    assertThat(store.restore(itemId, a, 1)).isEqualTo(RestoreResult.NOT_HELD);
    assertThat(stock(itemId)).isEqualTo(5);
    assertThat(bought(itemId, a)).isEqualTo(2);
  }

  @Test
  void restoreOnAClosedItemChangesNothing() {
    long itemId = open(5, 1);
    long a = user();
    assertThat(store.tryPurchase(itemId, a, 1)).isEqualTo(OK);
    store.deactivate(itemId);

    assertThat(store.restore(itemId, a, 1)).isEqualTo(RestoreResult.NOT_ACTIVE);
    assertThat(stock(itemId)).isEqualTo(4);
  }

  @Test
  void concurrentPurchasesAndRestoresKeepStockPlusBoughtEqualToTotal() throws Exception {
    int total = 50;
    long itemId = open(total, 1);
    int buyers = 200;
    List<Long> users = new ArrayList<>();
    for (int i = 0; i < buyers; i++) {
      users.add(user());
    }
    List<Callable<Object>> tasks = new ArrayList<>();
    for (long u : users) {
      tasks.add(
          () -> {
            if (store.tryPurchase(itemId, u, 1) == OK && u % 2 == 0) {
              store.restore(itemId, u, 1); // half of the winners cancel right away
            }
            return null;
          });
    }

    runTogether(tasks);

    assertThat(stock(itemId)).isBetween(0, total);
    assertThat(stock(itemId) + sumBought(itemId)).isEqualTo(total);
  }

  @Test
  void quantityBelowOneIsRejected() {
    assertThatThrownBy(() -> store.tryPurchase(1L, 1L, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.restore(1L, 1L, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
