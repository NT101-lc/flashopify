package com.flashopify.flashsale.service;

/** Public read API of the flash-sale module. */
public interface FlashSaleQueryService {

  /**
   * True when the product belongs to a campaign that is SCHEDULED or RUNNING.
   *
   * <p>Locking read (FOR SHARE); the caller must already hold the product row lock. FLS-015 must
   * lock each item's product row FOR SHARE and reject non-ACTIVE products when creating a campaign
   * or adding items; campaign status transitions need no product lock. Global lock order: products
   * row first, then inventory. A transaction that may write the products row must take FOR UPDATE
   * up front; never upgrade FOR SHARE to FOR UPDATE.
   */
  boolean isProductInActiveCampaign(long productId);
}
