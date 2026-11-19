package com.flashopify.flashsale.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.flashopify.common.web.MoneySerializer;
import java.math.BigDecimal;

/** Mirrors {@code AdminCampaignItem}. */
public record AdminCampaignItemResponse(
    long id,
    long productId,
    String productName,
    String productSlug,
    String thumbnailUrl,
    @JsonSerialize(using = MoneySerializer.class) BigDecimal originalPrice,
    @JsonSerialize(using = MoneySerializer.class) BigDecimal salePrice,
    int totalStock,
    int remainingStock,
    int limitPerUser,
    int sold) {}
