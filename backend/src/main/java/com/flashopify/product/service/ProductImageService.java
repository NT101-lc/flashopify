package com.flashopify.product.service;

import com.flashopify.product.dto.AddProductImageRequest;
import com.flashopify.product.dto.ImageUploadUrlRequest;
import com.flashopify.product.dto.ImageUploadUrlResponse;
import com.flashopify.product.dto.ProductImageResponse;
import com.flashopify.product.dto.ReorderImagesRequest;
import java.util.List;

/**
 * Gallery management. Upload is two steps: a presigned PUT into {@code products/{id}/tmp/}, then
 * registration, which verifies the object and copies it to its final key. Every write locks the
 * product row first ({@code FOR UPDATE}) so the 8-image limit and sort orders stay consistent.
 */
public interface ProductImageService {

  int MAX_IMAGES = 8;
  long MAX_BYTES = 5L * 1024 * 1024;

  ImageUploadUrlResponse createUploadUrl(long productId, ImageUploadUrlRequest request);

  ProductImageResponse addImage(long productId, AddProductImageRequest request);

  /** {@code imageIds} must be exactly the product's image ids; the index becomes the sort order. */
  List<ProductImageResponse> reorder(long productId, ReorderImagesRequest request);

  /** Removes the row, compacts sort orders, repairs the thumbnail; the object goes after commit. */
  void deleteImage(long productId, long imageId);
}
