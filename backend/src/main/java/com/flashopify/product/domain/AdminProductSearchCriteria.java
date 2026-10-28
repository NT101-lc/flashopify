package com.flashopify.product.domain;

import lombok.Builder;
import lombok.Value;

/** Filter, sort and paging input for the admin product list; null filters are ignored. */
@Value
@Builder
public class AdminProductSearchCriteria {

  /** Already LIKE-escaped by the service. */
  String q;

  ProductStatus status;
  Long categoryId;

  /** Only products whose available quantity is at or below this value (missing stock = 0). */
  Integer lowStock;

  AdminProductSort sort;
  long offset;
  int limit;
}
