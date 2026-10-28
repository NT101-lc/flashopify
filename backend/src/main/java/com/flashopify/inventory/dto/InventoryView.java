package com.flashopify.inventory.dto;

/** Read-only stock snapshot exposed to other modules. */
public record InventoryView(int quantity, int reserved, int available, int version) {

  public static InventoryView of(int quantity, int reserved, int version) {
    return new InventoryView(quantity, reserved, quantity - reserved, version);
  }
}
