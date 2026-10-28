package com.flashopify.product.domain;

import java.util.Arrays;
import lombok.Getter;

/** Whitelist of admin product sorts; the mapper picks a fixed SQL fragment per constant. */
@Getter
public enum AdminProductSort {
  UPDATED_AT_DESC("updatedAt,desc"),
  CREATED_AT_DESC("createdAt,desc"),
  NAME_ASC("name,asc"),
  PRICE_ASC("price,asc"),
  PRICE_DESC("price,desc"),
  AVAILABLE_ASC("available,asc");

  public static final String API_PATTERN =
      "updatedAt,desc|createdAt,desc|name,asc|price,asc|price,desc|available,asc";

  private final String apiValue;

  AdminProductSort(String apiValue) {
    this.apiValue = apiValue;
  }

  /** Parses an API value; null/blank gives the default, anything else not whitelisted is null. */
  public static AdminProductSort fromApi(String value) {
    if (value == null || value.isBlank()) {
      return UPDATED_AT_DESC;
    }
    return Arrays.stream(values()).filter(s -> s.apiValue.equals(value)).findFirst().orElse(null);
  }
}
