package com.flashopify.flashsale.service;

import com.flashopify.flashsale.domain.Campaign;
import com.flashopify.flashsale.domain.CampaignItem;
import java.time.Instant;
import java.util.OptionalInt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Real-time flash-sale stock in Redis (ADR-0002). Every operation is one Lua script over the item's
 * keys ({@link FlashSaleKeys}), so it is atomic even with many app instances.
 */
@Component
public class FlashSaleStockStore implements FlashSaleStockService {

  private static final RedisScript<Long> WARM_UP = script("warmup");
  private static final RedisScript<Long> DEACTIVATE = script("deactivate");
  private static final RedisScript<String> PURCHASE = script("purchase", String.class);
  private static final RedisScript<String> RESTORE = script("restore", String.class);

  private final StringRedisTemplate redis;

  public FlashSaleStockStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Loads the item's stock, window and limit; keys expire at {@code expireAt}. Never overwrites a
   * stock that already exists and never reactivates a deactivated item.
   *
   * @return the stock now in Redis
   */
  public long warmUp(Campaign campaign, CampaignItem item, Instant expireAt) {
    Long stock =
        redis.execute(
            WARM_UP,
            FlashSaleKeys.all(item.getId()),
            String.valueOf(item.getTotalStock()),
            String.valueOf(campaign.getStartTime().toEpochMilli()),
            String.valueOf(campaign.getEndTime().toEpochMilli()),
            String.valueOf(item.getLimitPerUser()),
            String.valueOf(expireAt.toEpochMilli()));
    return stock == null ? 0 : stock;
  }

  /**
   * Stops purchases and restores of the item for good and returns its frozen remaining stock.
   * Idempotent.
   *
   * @return the remaining stock, or empty when the item's keys are missing (never warmed up, or
   *     lost/expired in Redis)
   */
  public OptionalInt deactivate(long itemId) {
    Long stock = redis.execute(DEACTIVATE, FlashSaleKeys.all(itemId));
    return stock == null || stock < 0 ? OptionalInt.empty() : OptionalInt.of(stock.intValue());
  }

  @Override
  public PurchaseResult tryPurchase(long itemId, long userId, int quantity) {
    return PurchaseResult.valueOf(run(PURCHASE, itemId, userId, quantity));
  }

  @Override
  public RestoreResult restore(long itemId, long userId, int quantity) {
    return RestoreResult.valueOf(run(RESTORE, itemId, userId, quantity));
  }

  private String run(RedisScript<String> script, long itemId, long userId, int quantity) {
    if (quantity < 1) {
      throw new IllegalArgumentException("quantity must be at least 1: " + quantity);
    }
    String code =
        redis.execute(
            script, FlashSaleKeys.all(itemId), String.valueOf(userId), String.valueOf(quantity));
    if (code == null) {
      throw new IllegalStateException("Flash-sale script returned no result for item " + itemId);
    }
    return code;
  }

  static RedisScript<Long> script(String name) {
    return script(name, Long.class);
  }

  static <T> RedisScript<T> script(String name, Class<T> type) {
    return RedisScript.of(new ClassPathResource("scripts/flashsale/" + name + ".lua"), type);
  }
}
