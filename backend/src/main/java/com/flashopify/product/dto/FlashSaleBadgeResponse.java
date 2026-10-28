package com.flashopify.product.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.flashopify.common.web.MoneySerializer;
import java.math.BigDecimal;
import java.time.Instant;

/** Mirrors {@code FlashSaleBadge}; filled in once the flashsale module exists. */
public record FlashSaleBadgeResponse(
    long itemId,
    @JsonSerialize(using = MoneySerializer.class) BigDecimal salePrice,
    Instant endTime) {}
