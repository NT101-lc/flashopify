package com.flashopify.product.service;

import com.flashopify.inventory.service.InventoryService;
import com.flashopify.product.domain.Category;
import com.flashopify.product.domain.Product;
import com.flashopify.product.domain.ProductImage;
import com.flashopify.product.domain.ProductStatus;
import com.flashopify.product.dto.ProductImportCommand;
import com.flashopify.product.mapper.CategoryMapper;
import com.flashopify.product.mapper.ProductImageMapper;
import com.flashopify.product.mapper.ProductMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A failed INSERT on a unique key only undoes that statement in InnoDB, so a duplicate slug/SKU is
 * retried inside the same transaction with a deterministic suffix. Any other failure (including the
 * inventory insert) rolls the whole product back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImportServiceImpl implements ProductImportService {

  static final int MAX_IMAGES = 8;

  private final ProductMapper productMapper;
  private final CategoryMapper categoryMapper;
  private final ProductImageMapper imageMapper;
  private final InventoryService inventoryService;

  @Override
  public long upsertCategory(String slug, String name) {
    Category existing = categoryMapper.findBySlug(slug);
    if (existing != null) {
      return existing.getId();
    }
    Category created = Category.builder().slug(slug).name(name).build();
    try {
      categoryMapper.insert(created);
      return created.getId();
    } catch (DuplicateKeyException e) {
      // Lost a race with another writer: the row exists now.
      return categoryMapper.findBySlug(slug).getId();
    }
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsBySourceId(int sourceId) {
    return productMapper.existsBySourceId(sourceId);
  }

  @Override
  @Transactional
  public Optional<Long> importProduct(ProductImportCommand cmd) {
    Product product =
        Product.builder()
            .categoryId(cmd.categoryId())
            .sourceId(cmd.sourceId())
            .sku(cmd.sku())
            .name(cmd.name())
            .slug(cmd.slug())
            .description(cmd.description())
            .brand(cmd.brand())
            .price(cmd.price())
            .rating(cmd.rating())
            .thumbnailUrl(cmd.thumbnailUrl())
            .status(ProductStatus.ACTIVE)
            .build();
    boolean slugRetried = false;
    boolean skuRetried = false;
    while (true) {
      try {
        productMapper.insert(product);
        break;
      } catch (DuplicateKeyException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uk_products_source")) {
          log.info("product skipped, source id already imported sourceId={}", cmd.sourceId());
          return Optional.empty();
        }
        if (message.contains("uk_products_slug") && !slugRetried) {
          slugRetried = true;
          product.setSlug(cmd.slug() + "-" + cmd.sourceId());
        } else if (message.contains("uk_products_sku") && !skuRetried) {
          skuRetried = true;
          product.setSku("DJ-" + cmd.sourceId());
        } else {
          throw e;
        }
      }
    }
    insertImages(product.getId(), cmd.imageUrls());
    inventoryService.initialize(product.getId(), cmd.stock());
    return Optional.of(product.getId());
  }

  @Override
  @Transactional(readOnly = true)
  public long countExternalImageUrls(String host) {
    return productMapper.countByThumbnailContaining(host) + imageMapper.countByUrlContaining(host);
  }

  private void insertImages(long productId, List<String> urls) {
    if (urls == null || urls.isEmpty()) {
      return;
    }
    List<ProductImage> rows = new ArrayList<>();
    for (int i = 0; i < urls.size() && i < MAX_IMAGES; i++) {
      rows.add(ProductImage.builder().productId(productId).url(urls.get(i)).sortOrder(i).build());
    }
    imageMapper.insertBatch(rows);
  }
}
