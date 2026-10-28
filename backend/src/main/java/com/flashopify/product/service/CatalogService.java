package com.flashopify.product.service;

import com.flashopify.common.web.PageResponse;
import com.flashopify.product.dto.CategoryResponse;
import com.flashopify.product.dto.ProductDetailResponse;
import com.flashopify.product.dto.ProductSummaryResponse;
import java.math.BigDecimal;
import java.util.List;

/** Public, read-only catalog. Only ACTIVE products are ever visible. */
public interface CatalogService {

  /**
   * Filtered, sorted page. {@code sort} is an API value (null = default); unknown category slug
   * gives an empty page; minPrice greater than maxPrice is a VALIDATION_ERROR.
   */
  PageResponse<ProductSummaryResponse> search(
      String q,
      String categorySlug,
      List<String> brands,
      BigDecimal minPrice,
      BigDecimal maxPrice,
      BigDecimal minRating,
      String sort,
      int page,
      int size);

  List<String> brands(String categorySlug);

  /** Cache-aside; 404 PRODUCT_NOT_FOUND for missing, INACTIVE or DELETED products. */
  ProductDetailResponse getBySlug(String slug);

  List<CategoryResponse> categoryTree();
}
