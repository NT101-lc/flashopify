package com.flashopify.product.service;

import com.flashopify.product.dto.ProductImportCommand;
import java.util.Optional;

/** Bulk-import entry point of the product module, used by the seed job in {@code admin.seed}. */
public interface ProductImportService {

  /** Id of the category with this slug; creates it (with {@code name}) when missing. */
  long upsertCategory(String slug, String name);

  boolean existsBySourceId(int sourceId);

  /**
   * Creates the ACTIVE product, its images and its inventory in one transaction. A slug or SKU that
   * is already taken by another product is made unique ({@code slug-{sourceId}}, {@code
   * DJ-{sourceId}}).
   *
   * @return the new product id, or empty when a product with this source id already exists
   */
  Optional<Long> importProduct(ProductImportCommand command);

  /** Number of stored image URLs (thumbnails and gallery) containing {@code host}. */
  long countExternalImageUrls(String host);
}
