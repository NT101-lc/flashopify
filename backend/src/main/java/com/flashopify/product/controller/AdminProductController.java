package com.flashopify.product.controller;

import com.flashopify.common.web.PageResponse;
import com.flashopify.product.domain.AdminProductSort;
import com.flashopify.product.domain.ProductStatus;
import com.flashopify.product.dto.AdminProductDetailResponse;
import com.flashopify.product.dto.AdminProductSummaryResponse;
import com.flashopify.product.dto.InventoryResponse;
import com.flashopify.product.dto.ProductUpsertRequest;
import com.flashopify.product.dto.UpdateInventoryRequest;
import com.flashopify.product.service.AdminProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin product management; the ADMIN role is enforced for {@code /api/v1/admin/**}. */
@Validated
@RestController
@RequestMapping("/api/v1/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

  private final AdminProductService service;

  @GetMapping
  public PageResponse<AdminProductSummaryResponse> list(
      @RequestParam(required = false) @Size(max = 100) String q,
      @RequestParam(required = false) ProductStatus status,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) @Min(0) Integer lowStock,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) @Pattern(regexp = AdminProductSort.API_PATTERN) String sort) {
    return service.list(q, status, categoryId, lowStock, sort, page, size);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AdminProductDetailResponse create(@Valid @RequestBody ProductUpsertRequest request) {
    return service.create(request);
  }

  @GetMapping("/{productId}")
  public AdminProductDetailResponse get(@PathVariable long productId) {
    return service.get(productId);
  }

  @PutMapping("/{productId}")
  public AdminProductDetailResponse update(
      @PathVariable long productId, @Valid @RequestBody ProductUpsertRequest request) {
    return service.update(productId, request);
  }

  @DeleteMapping("/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable long productId) {
    service.delete(productId);
  }

  @PutMapping("/{productId}/inventory")
  public InventoryResponse updateInventory(
      @PathVariable long productId, @Valid @RequestBody UpdateInventoryRequest request) {
    return service.updateInventory(productId, request);
  }
}
