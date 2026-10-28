package com.flashopify.product.service;

import com.flashopify.common.web.PageResponse;
import com.flashopify.product.domain.ProductStatus;
import com.flashopify.product.dto.AdminProductDetailResponse;
import com.flashopify.product.dto.AdminProductSummaryResponse;
import com.flashopify.product.dto.InventoryResponse;
import com.flashopify.product.dto.ProductUpsertRequest;
import com.flashopify.product.dto.UpdateInventoryRequest;

/**
 * Admin product management (all statuses). Global lock order: always lock the {@code products} row
 * first ({@code FOR SHARE} or {@code FOR UPDATE}), then {@code inventory}. A transaction that may
 * write the products row must take FOR UPDATE up front; never upgrade FOR SHARE to FOR UPDATE.
 */
public interface AdminProductService {

  PageResponse<AdminProductSummaryResponse> list(
      String q,
      ProductStatus status,
      Long categoryId,
      Integer lowStock,
      String sort,
      int page,
      int size);

  AdminProductDetailResponse get(long productId);

  /** Creates the product and its inventory row (initialStock, default 0) in one transaction. */
  AdminProductDetailResponse create(ProductUpsertRequest request);

  AdminProductDetailResponse update(long productId, ProductUpsertRequest request);

  /**
   * Soft delete (status DELETED); idempotent. Rejected while the product is in a SCHEDULED or
   * RUNNING campaign.
   *
   * <p>Contract for FLS-015: adding a product to a campaign must lock the same product row (a
   * method on this module's service that runs {@code SELECT ... FOR UPDATE}) and reject non-ACTIVE
   * products, otherwise a concurrent delete and add can both pass their checks.
   */
  void delete(long productId);

  InventoryResponse updateInventory(long productId, UpdateInventoryRequest request);
}
