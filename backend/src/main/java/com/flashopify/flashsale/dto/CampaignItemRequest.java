package com.flashopify.flashsale.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Mirrors {@code CampaignItemRequest}; {@code limitPerUser} defaults to 1. */
public record CampaignItemRequest(
    @NotNull @Positive Long productId,
    @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal salePrice,
    @NotNull @Min(1) @Max(1_000_000) Integer totalStock,
    @Min(1) @Max(1_000) Integer limitPerUser) {

  public int limitOrDefault() {
    return limitPerUser == null ? 1 : limitPerUser;
  }
}
