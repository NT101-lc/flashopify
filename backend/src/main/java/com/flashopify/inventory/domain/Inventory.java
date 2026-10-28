package com.flashopify.inventory.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Row of the inventory table. Owned by the inventory module. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Inventory {

  private Long productId;
  private int quantity;
  private int reserved;
  private int version;
  private Instant updatedAt;
}
