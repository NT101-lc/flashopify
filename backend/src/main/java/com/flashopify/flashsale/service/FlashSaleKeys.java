package com.flashopify.flashsale.service;

import java.util.List;

/**
 * Redis keys of one flash-sale item. All share the hash tag {@code {itemId}} so they live in the
 * same cluster slot and one Lua script may touch them all (ADR-0002).
 *
 * <ul>
 *   <li>{@code fs:stock:{id}}: remaining units (string integer)
 *   <li>{@code fs:bought:{id}}: hash userId → units bought
 *   <li>{@code fs:meta:{id}}: hash total, start/end (epoch ms), limit, active (1/0)
 * </ul>
 */
public final class FlashSaleKeys {

  private FlashSaleKeys() {}

  public static String stock(long itemId) {
    return "fs:stock:{" + itemId + "}";
  }

  public static String bought(long itemId) {
    return "fs:bought:{" + itemId + "}";
  }

  public static String meta(long itemId) {
    return "fs:meta:{" + itemId + "}";
  }

  /** stock, bought, meta: the KEYS order every flash-sale script expects. */
  public static List<String> all(long itemId) {
    return List.of(stock(itemId), bought(itemId), meta(itemId));
  }
}
