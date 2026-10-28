package com.flashopify.product.dto;

import com.flashopify.inventory.dto.InventoryView;

/** Mirrors {@code Inventory}. */
public record InventoryResponse(int quantity, int reserved, int available, int version) {

  public static InventoryResponse from(InventoryView view) {
    return new InventoryResponse(
        view.quantity(), view.reserved(), view.available(), view.version());
  }

  /** Shown for a product that (unexpectedly) has no inventory row. */
  public static InventoryResponse empty() {
    return new InventoryResponse(0, 0, 0, 0);
  }
}
