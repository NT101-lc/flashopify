package com.flashopify.product.service;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.web.PageResponse;
import com.flashopify.flashsale.service.FlashSaleQueryService;
import com.flashopify.inventory.dto.InventoryView;
import com.flashopify.inventory.service.InventoryService;
import com.flashopify.product.domain.AdminProductSearchCriteria;
import com.flashopify.product.domain.AdminProductSort;
import com.flashopify.product.domain.Product;
import com.flashopify.product.domain.ProductChangedEvent;
import com.flashopify.product.domain.ProductImage;
import com.flashopify.product.domain.ProductStatus;
import com.flashopify.product.dto.AdminProductDetailResponse;
import com.flashopify.product.dto.AdminProductSummaryResponse;
import com.flashopify.product.dto.InventoryResponse;
import com.flashopify.product.dto.ProductImageResponse;
import com.flashopify.product.dto.ProductUpsertRequest;
import com.flashopify.product.dto.UpdateInventoryRequest;
import com.flashopify.product.mapper.CategoryMapper;
import com.flashopify.product.mapper.ProductImageMapper;
import com.flashopify.product.mapper.ProductMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes lock the product row first ({@code SELECT ... FOR UPDATE}) so update, delete and inventory
 * changes of one product are serialised. Duplicate SKU/slug are detected by the unique keys (no
 * pre-check), so concurrent creates cannot both succeed. Cache eviction happens after commit via
 * {@link ProductChangedEvent}.
 */
@Service
@RequiredArgsConstructor
public class AdminProductServiceImpl implements AdminProductService {

  private final ProductMapper productMapper;
  private final CategoryMapper categoryMapper;
  private final ProductImageMapper imageMapper;
  private final InventoryService inventoryService;
  private final FlashSaleQueryService flashSaleQueryService;
  private final ApplicationEventPublisher events;

  @Override
  @Transactional(readOnly = true)
  public PageResponse<AdminProductSummaryResponse> list(
      String q,
      ProductStatus status,
      Long categoryId,
      Integer lowStock,
      String sort,
      int page,
      int size) {
    AdminProductSort productSort = AdminProductSort.fromApi(sort);
    if (productSort == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Unsupported sort: " + sort);
    }
    AdminProductSearchCriteria criteria =
        AdminProductSearchCriteria.builder()
            .q(CatalogServiceImpl.escapeLike(q))
            .status(status)
            .categoryId(categoryId)
            .lowStock(lowStock)
            .sort(productSort)
            .offset((long) page * size)
            .limit(size)
            .build();
    long total = productMapper.adminCount(criteria);
    if (total == 0) {
      return PageResponse.of(List.of(), page, size, 0);
    }
    List<Product> products = productMapper.adminSearch(criteria);
    Map<Long, InventoryView> stock =
        inventoryService.findByProductIds(products.stream().map(Product::getId).toList());
    List<AdminProductSummaryResponse> items =
        products.stream().map(p -> toSummary(p, stock.get(p.getId()))).toList();
    return PageResponse.of(items, page, size, total);
  }

  @Override
  @Transactional(readOnly = true)
  public AdminProductDetailResponse get(long productId) {
    return loadDetail(productId);
  }

  @Override
  @Transactional
  public AdminProductDetailResponse create(ProductUpsertRequest request) {
    requireCategory(request.categoryId());
    Product product = toProduct(request);
    try {
      productMapper.insert(product);
    } catch (DuplicateKeyException e) {
      throw duplicate(e);
    }
    inventoryService.initialize(
        product.getId(), request.initialStock() == null ? 0 : request.initialStock());
    events.publishEvent(new ProductChangedEvent(product.getId(), Set.of(product.getSlug())));
    return loadDetail(product.getId());
  }

  @Override
  @Transactional
  public AdminProductDetailResponse update(long productId, ProductUpsertRequest request) {
    Product existing = lockExisting(productId);
    requireCategory(request.categoryId());
    Product changed = toProduct(request);
    changed.setId(productId);
    if (existing.getStatus() == ProductStatus.ACTIVE
        && changed.getStatus() == ProductStatus.INACTIVE
        && flashSaleQueryService.isProductInActiveCampaign(productId)) {
      throw new BusinessException(
          ErrorCode.PRODUCT_IN_ACTIVE_CAMPAIGN,
          "Product is part of a scheduled or running campaign");
    }
    try {
      productMapper.update(changed);
    } catch (DuplicateKeyException e) {
      throw duplicate(e);
    }
    Set<String> slugs = new HashSet<>();
    slugs.add(existing.getSlug());
    slugs.add(changed.getSlug());
    events.publishEvent(new ProductChangedEvent(productId, slugs));
    return loadDetail(productId);
  }

  @Override
  @Transactional
  public void delete(long productId) {
    Product existing = productMapper.lockById(productId);
    if (existing == null) {
      throw notFound();
    }
    if (existing.getStatus() == ProductStatus.DELETED) {
      return;
    }
    if (flashSaleQueryService.isProductInActiveCampaign(productId)) {
      throw new BusinessException(
          ErrorCode.PRODUCT_IN_ACTIVE_CAMPAIGN,
          "Product is part of a scheduled or running campaign");
    }
    productMapper.softDelete(productId);
    events.publishEvent(new ProductChangedEvent(productId, Set.of(existing.getSlug())));
  }

  @Override
  @Transactional
  public InventoryResponse updateInventory(long productId, UpdateInventoryRequest request) {
    // FOR SHARE: lock order is products before inventory.
    Product existing = productMapper.lockByIdShared(productId);
    if (existing == null) {
      throw notFound();
    }
    if (existing.getStatus() == ProductStatus.DELETED) {
      throw new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE, "Product is deleted");
    }
    // Stock is overlaid live on the public detail, so no cache eviction is needed.
    return InventoryResponse.from(
        inventoryService.updateQuantity(productId, request.quantity(), request.version()));
  }

  private Product lockExisting(long productId) {
    Product existing = productMapper.lockById(productId);
    if (existing == null || existing.getStatus() == ProductStatus.DELETED) {
      throw notFound();
    }
    return existing;
  }

  private void requireCategory(long categoryId) {
    if (!categoryMapper.existsById(categoryId)) {
      throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found");
    }
  }

  private AdminProductDetailResponse loadDetail(long productId) {
    Product p = productMapper.findById(productId);
    if (p == null) {
      throw notFound();
    }
    InventoryView inv = inventoryService.findByProductIds(List.of(productId)).get(productId);
    List<ProductImageResponse> images =
        imageMapper.findByProductId(productId).stream()
            .map(AdminProductServiceImpl::toImage)
            .toList();
    return new AdminProductDetailResponse(
        p.getId(),
        p.getSku(),
        p.getName(),
        p.getSlug(),
        p.getBrand(),
        p.getPrice(),
        p.getStatus(),
        p.getThumbnailUrl(),
        p.getCategoryId(),
        toInventory(inv),
        p.getUpdatedAt(),
        p.getDescription(),
        p.getRating(),
        p.getSourceId(),
        images,
        p.getCreatedAt());
  }

  private static AdminProductSummaryResponse toSummary(Product p, InventoryView inv) {
    return new AdminProductSummaryResponse(
        p.getId(),
        p.getSku(),
        p.getName(),
        p.getSlug(),
        p.getBrand(),
        p.getPrice(),
        p.getStatus(),
        p.getThumbnailUrl(),
        p.getCategoryId(),
        toInventory(inv),
        p.getUpdatedAt());
  }

  private static InventoryResponse toInventory(InventoryView inv) {
    return inv == null ? InventoryResponse.empty() : InventoryResponse.from(inv);
  }

  private static ProductImageResponse toImage(ProductImage i) {
    return new ProductImageResponse(i.getId(), i.getUrl(), i.getSortOrder());
  }

  private static Product toProduct(ProductUpsertRequest r) {
    return Product.builder()
        .categoryId(r.categoryId())
        .sku(r.sku().trim())
        .name(r.name().trim())
        .slug(r.slug())
        .description(r.description())
        .brand(r.brand())
        .price(r.price())
        .status(r.productStatus())
        .build();
  }

  /** Maps the violated unique key to its business error; any other duplicate is a real bug. */
  private static BusinessException duplicate(DuplicateKeyException e) {
    String message = String.valueOf(e.getMostSpecificCause().getMessage());
    if (message.contains("uk_products_sku")) {
      return new BusinessException(ErrorCode.DUPLICATE_SKU, "SKU already exists");
    }
    if (message.contains("uk_products_slug")) {
      return new BusinessException(ErrorCode.DUPLICATE_SLUG, "Slug already exists");
    }
    throw e;
  }

  private static BusinessException notFound() {
    return new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found");
  }
}
