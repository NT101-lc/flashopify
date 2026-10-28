package com.flashopify.product.domain;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/** Filter, sort and paging input for the public product search; null filters are ignored. */
@Value
@Builder
public class ProductSearchCriteria {

  /** Already LIKE-escaped by the service. */
  String q;

  List<Long> categoryIds;
  List<String> brands;
  BigDecimal minPrice;
  BigDecimal maxPrice;
  BigDecimal minRating;
  ProductSort sort;
  long offset;
  int limit;
}
