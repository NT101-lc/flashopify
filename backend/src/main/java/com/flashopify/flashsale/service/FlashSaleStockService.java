package com.flashopify.flashsale.service;

/**
 * Public API of the flash-sale stock in Redis (ADR-0002). Both operations are a single atomic Lua
 * script; no MySQL access, no lock, no LLM. Callers make them idempotent per order (FLS-018/029): a
 * script itself cannot tell a retry from a second purchase.
 */
public interface FlashSaleStockService {

  /** Mirrors the purchase codes of ADR-0002. */
  enum PurchaseResult {
    OK,
    /** Not warmed up yet, outside the window, cancelled or ended. */
    NOT_ACTIVE,
    /** The user would exceed {@code limitPerUser}. */
    LIMIT_EXCEEDED,
    SOLD_OUT
  }

  enum RestoreResult {
    /** Units are back in the pool and the user may buy them again. */
    OK,
    /** The user does not hold that many units, or stock would exceed {@code totalStock}. */
    NOT_HELD,
    /**
     * The item is closed or its keys are gone; nothing changed in Redis and the caller must release
     * the MySQL reservation itself (the end-of-campaign sync has already counted the unit as sold).
     */
    NOT_ACTIVE
  }

  /**
   * Checks the window, then the per-user limit, then the stock, and deducts on success.
   *
   * @throws IllegalArgumentException when {@code quantity < 1}
   */
  PurchaseResult tryPurchase(long itemId, long userId, int quantity);

  /**
   * Reverses a successful purchase; never pushes the stock above {@code totalStock}.
   *
   * @throws IllegalArgumentException when {@code quantity < 1}
   */
  RestoreResult restore(long itemId, long userId, int quantity);
}
