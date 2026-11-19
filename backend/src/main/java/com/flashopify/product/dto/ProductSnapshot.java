package com.flashopify.product.dto;

import com.flashopify.product.domain.ProductStatus;
import java.math.BigDecimal;

/** Read-only view of a product exposed to other modules (any status). */
public record ProductSnapshot(
    long id,
    String name,
    String slug,
    BigDecimal price,
    String thumbnailUrl,
    ProductStatus status) {

  public boolean isActive() {
    return status == ProductStatus.ACTIVE;
  }
}
