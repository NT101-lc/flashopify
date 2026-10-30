package com.flashopify.product.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Number of ACTIVE products directly in one category. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryProductCount {

  private Long categoryId;
  private long productCount;
}
