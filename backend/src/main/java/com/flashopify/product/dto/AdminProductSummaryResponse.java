package com.flashopify.product.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.flashopify.common.web.MoneySerializer;
import com.flashopify.product.domain.ProductStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Mirrors {@code AdminProductSummary}. */
public record AdminProductSummaryResponse(
    long id,
    String sku,
    String name,
    String slug,
    String brand,
    @JsonSerialize(using = MoneySerializer.class) BigDecimal price,
    ProductStatus status,
    String thumbnailUrl,
    long categoryId,
    InventoryResponse inventory,
    Instant updatedAt) {}
