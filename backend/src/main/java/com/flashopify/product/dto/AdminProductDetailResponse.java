package com.flashopify.product.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.flashopify.common.web.MoneySerializer;
import com.flashopify.product.domain.ProductStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Mirrors {@code AdminProductDetail} (AdminProductSummary fields flattened plus detail fields). */
public record AdminProductDetailResponse(
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
    Instant updatedAt,
    String description,
    BigDecimal rating,
    Integer sourceId,
    List<ProductImageResponse> images,
    Instant createdAt) {}
