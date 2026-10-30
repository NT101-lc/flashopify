package com.flashopify.product.controller;

import com.flashopify.common.web.PageResponse;
import com.flashopify.product.domain.ProductSort;
import com.flashopify.product.dto.ProductDetailResponse;
import com.flashopify.product.dto.ProductSummaryResponse;
import com.flashopify.product.service.CatalogService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public product catalog (no authentication). */
@Validated
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class CatalogController {

  private final CatalogService catalogService;

  @GetMapping
  public PageResponse<ProductSummaryResponse> list(
      @RequestParam(required = false) @Size(max = 100) String q,
      @RequestParam(required = false) String categorySlug,
      @RequestParam(name = "brand", required = false) List<String> brands,
      @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
      @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
      @RequestParam(required = false) @DecimalMin("0") @DecimalMax("5") BigDecimal minRating,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) @Pattern(regexp = ProductSort.API_PATTERN) String sort) {
    return catalogService.search(
        q, categorySlug, brands, minPrice, maxPrice, minRating, sort, page, size);
  }

  @GetMapping("/brands")
  public List<String> brands(@RequestParam(required = false) String categorySlug) {
    return catalogService.brands(categorySlug);
  }

  @GetMapping("/{slug}")
  public ProductDetailResponse detail(@PathVariable String slug) {
    return catalogService.getBySlug(slug);
  }
}
