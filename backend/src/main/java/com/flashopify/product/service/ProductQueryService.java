package com.flashopify.product.service;

import com.flashopify.product.dto.ProductSnapshot;
import java.util.Collection;
import java.util.Map;

/**
 * Public read API of the product module for other modules.
 *
 * <p>Global lock order: {@code products} rows first (ascending id), then flash-sale campaign/item
 * rows, then {@code inventory}.
 */
public interface ProductQueryService {

  /** Snapshots by id, without locking; missing ids are absent from the map. */
  Map<Long, ProductSnapshot> findByIds(Collection<Long> productIds);

  /**
   * Locks every product row {@code FOR SHARE}, one at a time in ascending id order, and returns
   * their snapshots (any status; missing ids are absent). Must run inside the caller's transaction;
   * the locks are held until it ends. Never call this in a transaction that will later write a
   * products row (no FOR SHARE to FOR UPDATE upgrade).
   */
  Map<Long, ProductSnapshot> lockForShare(Collection<Long> productIds);
}
