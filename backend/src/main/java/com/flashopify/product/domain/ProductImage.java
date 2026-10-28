package com.flashopify.product.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Row of the product_images table. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductImage {

  private Long id;
  private Long productId;
  private String url;
  private int sortOrder;
  private Instant createdAt;
}
