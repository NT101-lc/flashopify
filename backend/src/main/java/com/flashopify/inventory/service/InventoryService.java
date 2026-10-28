package com.flashopify.inventory.service;

import com.flashopify.inventory.dto.InventoryView;
import java.util.Collection;
import java.util.Map;

/** Public read API of the inventory module. */
public interface InventoryService {

  /** Stock per product id in one query; products without an inventory row are absent. */
  Map<Long, InventoryView> findByProductIds(Collection<Long> productIds);

  /** {@code quantity - reserved}, or 0 when the product has no inventory row. */
  int availableOf(long productId);
}
