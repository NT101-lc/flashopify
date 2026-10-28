package com.flashopify.product.domain;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Row of the products table; {@code categorySlug}/{@code categoryName} come from the join. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

  private Long id;
  private Long categoryId;
  private String categorySlug;
  private String categoryName;
  private String sku;
  private String name;
  private String slug;
  private String description;
  private String brand;
  private BigDecimal price;
  private BigDecimal rating;
  private String thumbnailUrl;
  private Integer sourceId;
  private ProductStatus status;
  private Instant createdAt;
  private Instant updatedAt;
}
