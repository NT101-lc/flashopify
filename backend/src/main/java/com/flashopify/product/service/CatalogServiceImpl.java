package com.flashopify.product.service;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.web.PageResponse;
import com.flashopify.inventory.dto.InventoryView;
import com.flashopify.inventory.service.InventoryService;
import com.flashopify.product.domain.Category;
import com.flashopify.product.domain.CategoryProductCount;
import com.flashopify.product.domain.Product;
import com.flashopify.product.domain.ProductImage;
import com.flashopify.product.domain.ProductSearchCriteria;
import com.flashopify.product.domain.ProductSort;
import com.flashopify.product.dto.CategoryRefResponse;
import com.flashopify.product.dto.CategoryResponse;
import com.flashopify.product.dto.ProductDetailResponse;
import com.flashopify.product.dto.ProductImageResponse;
import com.flashopify.product.dto.ProductSummaryResponse;
import com.flashopify.product.mapper.CategoryMapper;
import com.flashopify.product.mapper.ProductImageMapper;
import com.flashopify.product.mapper.ProductMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogServiceImpl implements CatalogService {

  private static final Comparator<CategoryResponse> BY_NAME =
      Comparator.comparing(CategoryResponse::name).thenComparing(CategoryResponse::id);

  private final ProductMapper productMapper;
  private final CategoryMapper categoryMapper;
  private final ProductImageMapper imageMapper;
  private final InventoryService inventoryService;
  private final ProductDetailCache detailCache;

  @Override
  public PageResponse<ProductSummaryResponse> search(
      String q,
      String categorySlug,
      List<String> brands,
      BigDecimal minPrice,
      BigDecimal maxPrice,
      BigDecimal minRating,
      String sort,
      int page,
      int size) {
    if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "minPrice must not be greater than maxPrice");
    }
    ProductSort productSort = ProductSort.fromApi(sort);
    if (productSort == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Unsupported sort: " + sort);
    }
    List<Long> categoryIds = null;
    if (categorySlug != null && !categorySlug.isBlank()) {
      categoryIds = categorySubtreeIds(categorySlug);
      if (categoryIds.isEmpty()) {
        return PageResponse.of(List.of(), page, size, 0);
      }
    }
    ProductSearchCriteria criteria =
        ProductSearchCriteria.builder()
            .q(escapeLike(q))
            .categoryIds(categoryIds)
            .brands(brands == null || brands.isEmpty() ? null : brands)
            .minPrice(minPrice)
            .maxPrice(maxPrice)
            .minRating(minRating)
            .sort(productSort)
            .offset((long) page * size)
            .limit(size)
            .build();

    long total = productMapper.countActive(criteria);
    if (total == 0) {
      return PageResponse.of(List.of(), page, size, 0);
    }
    List<Product> products = productMapper.searchActive(criteria);
    Map<Long, InventoryView> stock =
        inventoryService.findByProductIds(products.stream().map(Product::getId).toList());
    List<ProductSummaryResponse> items =
        products.stream()
            .map(
                p -> {
                  InventoryView inv = stock.get(p.getId());
                  return toSummary(p, inv != null && inv.available() > 0);
                })
            .toList();
    return PageResponse.of(items, page, size, total);
  }

  @Override
  public List<String> brands(String categorySlug) {
    List<Long> categoryIds = null;
    if (categorySlug != null && !categorySlug.isBlank()) {
      categoryIds = categorySubtreeIds(categorySlug);
      if (categoryIds.isEmpty()) {
        return List.of();
      }
    }
    return productMapper.findDistinctActiveBrands(categoryIds);
  }

  @Override
  public ProductDetailResponse getBySlug(String slug) {
    ProductDetailResponse base = detailCache.get(slug).orElseGet(() -> loadDetail(slug));
    return base.withStock(inventoryService.availableOf(base.id()));
  }

  @Override
  public List<CategoryResponse> categoryTree() {
    List<Category> all = categoryMapper.findAll();
    Map<Long, Long> direct = new HashMap<>();
    for (CategoryProductCount c : categoryMapper.countActiveProductsByCategory()) {
      direct.put(c.getCategoryId(), c.getProductCount());
    }
    Map<Long, List<Category>> byParent = new HashMap<>();
    List<Category> roots = new ArrayList<>();
    for (Category c : all) {
      if (c.getParentId() == null) {
        roots.add(c);
      } else {
        byParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
      }
    }
    return roots.stream().map(r -> toNode(r, byParent, direct)).sorted(BY_NAME).toList();
  }

  private CategoryResponse toNode(
      Category category, Map<Long, List<Category>> byParent, Map<Long, Long> direct) {
    List<CategoryResponse> children =
        byParent.getOrDefault(category.getId(), List.of()).stream()
            .map(c -> toNode(c, byParent, direct))
            .sorted(BY_NAME)
            .toList();
    long count =
        direct.getOrDefault(category.getId(), 0L)
            + children.stream().mapToLong(CategoryResponse::productCount).sum();
    return new CategoryResponse(
        category.getId(), category.getSlug(), category.getName(), count, children);
  }

  /** The category and all its descendants; empty when the slug is unknown. */
  private List<Long> categorySubtreeIds(String slug) {
    Category root = categoryMapper.findBySlug(slug);
    if (root == null) {
      return List.of();
    }
    Map<Long, List<Long>> children = new HashMap<>();
    for (Category c : categoryMapper.findAll()) {
      if (c.getParentId() != null) {
        children.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c.getId());
      }
    }
    List<Long> queue = new ArrayList<>();
    queue.add(root.getId());
    for (int i = 0; i < queue.size(); i++) {
      queue.addAll(children.getOrDefault(queue.get(i), List.of()));
    }
    return queue;
  }

  private ProductDetailResponse loadDetail(String slug) {
    Product p = productMapper.findActiveBySlug(slug);
    if (p == null) {
      throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found");
    }
    List<ProductImageResponse> images =
        imageMapper.findByProductId(p.getId()).stream().map(CatalogServiceImpl::toImage).toList();
    // Stock fields are placeholders; getBySlug overlays live stock on every request.
    ProductDetailResponse detail =
        new ProductDetailResponse(
            p.getId(),
            p.getName(),
            p.getSlug(),
            p.getBrand(),
            p.getPrice(),
            p.getRating(),
            p.getThumbnailUrl(),
            p.getCategorySlug(),
            false,
            null,
            p.getSku(),
            p.getDescription(),
            new CategoryRefResponse(p.getCategoryId(), p.getCategorySlug(), p.getCategoryName()),
            0,
            images);
    detailCache.put(slug, detail);
    return detail;
  }

  private static ProductImageResponse toImage(ProductImage i) {
    return new ProductImageResponse(i.getId(), i.getUrl(), i.getSortOrder());
  }

  private static ProductSummaryResponse toSummary(Product p, boolean inStock) {
    return new ProductSummaryResponse(
        p.getId(),
        p.getName(),
        p.getSlug(),
        p.getBrand(),
        p.getPrice(),
        p.getRating(),
        p.getThumbnailUrl(),
        p.getCategorySlug(),
        inStock,
        null);
  }

  /** Escapes LIKE wildcards so user input is matched literally. */
  static String escapeLike(String q) {
    if (q == null) {
      return null;
    }
    String trimmed = q.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
