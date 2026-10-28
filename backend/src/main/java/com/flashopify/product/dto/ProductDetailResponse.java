package com.flashopify.product.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.flashopify.common.web.MoneySerializer;
import java.math.BigDecimal;
import java.util.List;

/**
 * Mirrors {@code ProductDetail} (ProductSummary fields flattened plus the detail fields). The Redis
 * cache keeps this record with stock fields zeroed; {@link #withStock} overlays live stock.
 */
public record ProductDetailResponse(
    long id,
    String name,
    String slug,
    String brand,
    @JsonSerialize(using = MoneySerializer.class) BigDecimal price,
    BigDecimal rating,
    String thumbnailUrl,
    String categorySlug,
    boolean inStock,
    FlashSaleBadgeResponse flashSale,
    String sku,
    String description,
    CategoryRefResponse category,
    int availableQuantity,
    List<ProductImageResponse> images) {

  public ProductDetailResponse withStock(int available) {
    return new ProductDetailResponse(
        id,
        name,
        slug,
        brand,
        price,
        rating,
        thumbnailUrl,
        categorySlug,
        available > 0,
        flashSale,
        sku,
        description,
        category,
        available,
        images);
  }
}
