package com.flashopify.inventory.service;

import com.flashopify.inventory.dto.InventoryView;
import java.util.Collection;
import java.util.Map;

/**
 * Public API of the inventory module.
 *
 * <p>Write contract for every writer of an inventory row (admin update today, reservations from
 * FLS-015 on): every write must bump {@code version}, and reservations must be conditional ({@code
 * reserved + n <= quantity}) so the admin's optimistic update and the CHECK constraints stay
 * authoritative.
 *
 * <p>Global lock order: always lock the {@code products} row first ({@code FOR SHARE} or {@code FOR
 * UPDATE}), then {@code inventory}. Every FLS-015+ reservation or campaign write must lock the
 * product row {@code FOR SHARE} before touching inventory, otherwise it can deadlock with admin
 * writes. A transaction that may write the products row must take FOR UPDATE up front; never
 * upgrade FOR SHARE to FOR UPDATE.
 */
public interface InventoryService {

  /** Stock per product id in one query; products without an inventory row are absent. */
  Map<Long, InventoryView> findByProductIds(Collection<Long> productIds);

  /** {@code quantity - reserved}, or 0 when the product has no inventory row. */
  int availableOf(long productId);

  /** Creates the inventory row of a new product (reserved 0, version 0). Joins the caller's tx. */
  void initialize(long productId, int quantity);

  /**
   * Sets the quantity if {@code expectedVersion} is current and {@code quantity >= reserved}.
   *
   * @throws com.flashopify.common.exception.BusinessException PRODUCT_NOT_FOUND (no row),
   *     CONCURRENT_MODIFICATION (stale version) or INSUFFICIENT_STOCK (below reserved)
   */
  InventoryView updateQuantity(long productId, int quantity, int expectedVersion);
}
