package com.flashopify.product.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.flashopify.common.web.MoneySerializer;
import java.math.BigDecimal;

/** Mirrors {@code ProductSummary}. */
public record ProductSummaryResponse(
    long id,
    String name,
    String slug,
    String brand,
    @JsonSerialize(using = MoneySerializer.class) BigDecimal price,
    BigDecimal rating,
    String thumbnailUrl,
    String categorySlug,
    boolean inStock,
    FlashSaleBadgeResponse flashSale) {}
