package com.flashopify.product.domain;

import java.util.Arrays;
import lombok.Getter;

/**
 * Whitelist of public product sorts. The mapper picks a fixed SQL fragment per constant, so no user
 * input ever reaches the ORDER BY clause.
 */
@Getter
public enum ProductSort {
  CREATED_AT_DESC("createdAt,desc"),
  PRICE_ASC("price,asc"),
  PRICE_DESC("price,desc"),
  RATING_DESC("rating,desc"),
  NAME_ASC("name,asc");

  public static final String API_PATTERN =
      "createdAt,desc|price,asc|price,desc|rating,desc|name,asc";

  private final String apiValue;

  ProductSort(String apiValue) {
    this.apiValue = apiValue;
  }

  /** Parses an API value; null/blank gives the default, anything else not whitelisted is null. */
  public static ProductSort fromApi(String value) {
    if (value == null || value.isBlank()) {
      return CREATED_AT_DESC;
    }
    return Arrays.stream(values()).filter(s -> s.apiValue.equals(value)).findFirst().orElse(null);
  }
}
