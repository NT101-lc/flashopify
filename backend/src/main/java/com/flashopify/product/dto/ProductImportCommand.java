package com.flashopify.product.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One product to import from an external catalogue (seed). All URLs must already point at our own
 * object storage.
 *
 * @param sourceId id in the source catalogue; the idempotency key of the import
 * @param imageUrls gallery images, at most 8 are stored
 */
public record ProductImportCommand(
    int sourceId,
    long categoryId,
    String sku,
    String name,
    String slug,
    String description,
    String brand,
    BigDecimal price,
    BigDecimal rating,
    int stock,
    String thumbnailUrl,
    List<String> imageUrls) {}
