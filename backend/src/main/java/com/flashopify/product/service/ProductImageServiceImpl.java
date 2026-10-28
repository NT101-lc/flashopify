package com.flashopify.product.service;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.storage.ObjectMetadata;
import com.flashopify.common.storage.ObjectStorage;
import com.flashopify.common.storage.PresignedUpload;
import com.flashopify.common.storage.StorageProperties;
import com.flashopify.product.domain.Product;
import com.flashopify.product.domain.ProductChangedEvent;
import com.flashopify.product.domain.ProductImage;
import com.flashopify.product.domain.ProductStatus;
import com.flashopify.product.dto.AddProductImageRequest;
import com.flashopify.product.dto.ImageUploadUrlRequest;
import com.flashopify.product.dto.ImageUploadUrlResponse;
import com.flashopify.product.dto.ProductImageResponse;
import com.flashopify.product.dto.ReorderImagesRequest;
import com.flashopify.product.mapper.ProductImageMapper;
import com.flashopify.product.mapper.ProductMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The client never chooses a storage key: upload keys are generated here and only keys of the form
 * {@code products/{productId}/tmp/{uuid}.{ext}} for the same product are accepted back. The
 * uploaded object is re-checked (size, Content-Type) at registration because the presigned URL is
 * only a hint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageServiceImpl implements ProductImageService {

  private static final Map<String, String> EXTENSIONS =
      Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

  private final ProductMapper productMapper;
  private final ProductImageMapper imageMapper;
  private final ObjectStorage storage;
  private final StorageProperties storageProperties;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public ImageUploadUrlResponse createUploadUrl(long productId, ImageUploadUrlRequest request) {
    Product product = productMapper.findById(productId);
    if (product == null || product.getStatus() == ProductStatus.DELETED) {
      throw productNotFound();
    }
    String contentType = normalize(request.contentType());
    String ext = EXTENSIONS.get(contentType);
    if (ext == null) {
      throw unsupportedType();
    }
    if (request.contentLength() > MAX_BYTES) {
      throw tooLarge();
    }
    if (imageMapper.countByProductId(productId) >= MAX_IMAGES) {
      throw limitExceeded();
    }
    // The file name is deliberately ignored: the key is server-generated.
    String key = "products/" + productId + "/tmp/" + UUID.randomUUID() + "." + ext;
    Duration ttl = storageProperties.presignTtl();
    PresignedUpload upload = storage.presignPut(key, contentType, request.contentLength(), ttl);
    return new ImageUploadUrlResponse(
        upload.url(), key, "PUT", upload.headers(), clock.instant().plus(ttl));
  }

  @Override
  @Transactional
  public ProductImageResponse addImage(long productId, AddProductImageRequest request) {
    Product product = lockExisting(productId);
    String tmpKey = request.objectKey();
    if (!tmpKeyPattern(productId).matcher(tmpKey).matches()) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "objectKey must come from this product's upload-url");
    }
    int count = imageMapper.countByProductId(productId);
    if (count >= MAX_IMAGES) {
      throw limitExceeded();
    }
    ObjectMetadata meta =
        storage
            .head(tmpKey)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.OBJECT_NOT_FOUND, "Uploaded file not found in storage"));
    String ext = meta.contentType() == null ? null : EXTENSIONS.get(normalize(meta.contentType()));
    if (meta.size() > MAX_BYTES || ext == null) {
      deleteQuietly(tmpKey);
      throw meta.size() > MAX_BYTES ? tooLarge() : unsupportedType();
    }

    String finalKey = "products/" + productId + "/" + UUID.randomUUID() + "." + ext;
    storage.copy(tmpKey, finalKey);
    // Registered right after the copy so a failing DB step below still cleans the final object up.
    afterCompletion(() -> deleteQuietly(tmpKey), () -> deleteQuietly(finalKey));

    int position =
        request.sortOrder() == null ? count : Math.max(0, Math.min(request.sortOrder(), count));
    if (position < count) {
      imageMapper.shiftFrom(productId, position);
    }
    ProductImage image =
        ProductImage.builder()
            .productId(productId)
            .url(storage.publicUrl(finalKey))
            .sortOrder(position)
            .build();
    imageMapper.insert(image);
    if (request.setAsThumbnail()
        || product.getThumbnailUrl() == null
        || product.getThumbnailUrl().isBlank()) {
      productMapper.updateThumbnail(productId, image.getUrl());
    }
    events.publishEvent(new ProductChangedEvent(productId, Set.of(product.getSlug())));
    return new ProductImageResponse(image.getId(), image.getUrl(), image.getSortOrder());
  }

  @Override
  @Transactional
  public List<ProductImageResponse> reorder(long productId, ReorderImagesRequest request) {
    Product product = lockExisting(productId);
    List<Long> requested = request.imageIds();
    List<ProductImage> current = imageMapper.findByProductId(productId);
    Set<Long> expected = new HashSet<>();
    current.forEach(i -> expected.add(i.getId()));
    if (new HashSet<>(requested).size() != requested.size()
        || !expected.equals(new HashSet<>(requested))) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR,
          "imageIds must contain every image of the product exactly once");
    }
    for (int i = 0; i < requested.size(); i++) {
      imageMapper.updateSortOrder(requested.get(i), productId, i);
    }
    events.publishEvent(new ProductChangedEvent(productId, Set.of(product.getSlug())));
    return imageMapper.findByProductId(productId).stream()
        .map(ProductImageServiceImpl::toDto)
        .toList();
  }

  @Override
  @Transactional
  public void deleteImage(long productId, long imageId) {
    Product product = lockExisting(productId);
    ProductImage image = imageMapper.findByIdAndProduct(imageId, productId);
    if (image == null) {
      throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND, "Image not found");
    }
    imageMapper.deleteByIdAndProduct(imageId, productId);
    List<ProductImage> remaining = imageMapper.findByProductId(productId);
    for (int i = 0; i < remaining.size(); i++) {
      ProductImage r = remaining.get(i);
      if (r.getSortOrder() != i) {
        imageMapper.updateSortOrder(r.getId(), productId, i);
      }
    }
    if (image.getUrl().equals(product.getThumbnailUrl())) {
      productMapper.updateThumbnail(
          productId, remaining.isEmpty() ? null : remaining.get(0).getUrl());
    }
    String key = keyOf(image.getUrl());
    if (key != null) {
      afterCompletion(() -> deleteQuietly(key), null);
    }
    events.publishEvent(new ProductChangedEvent(productId, Set.of(product.getSlug())));
  }

  /** Storage key behind a public URL, or null for URLs we do not own (never delete those). */
  private String keyOf(String url) {
    String prefix = storage.publicUrl("");
    if (prefix == null || !url.startsWith(prefix)) {
      return null;
    }
    String key = url.substring(prefix.length());
    return key.startsWith("products/") && !key.contains("..") ? key : null;
  }

  private Product lockExisting(long productId) {
    Product product = productMapper.lockById(productId);
    if (product == null || product.getStatus() == ProductStatus.DELETED) {
      throw productNotFound();
    }
    return product;
  }

  private void deleteQuietly(String key) {
    try {
      storage.delete(key);
    } catch (RuntimeException e) {
      log.warn("Could not delete storage object {}: {}", key, e.toString());
    }
  }

  /**
   * Runs {@code onCommit} after a successful commit and {@code onRollback} otherwise. Without an
   * active transaction the commit action runs immediately.
   */
  private static void afterCompletion(Runnable onCommit, Runnable onRollback) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      onCommit.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
              onCommit.run();
            } else if (onRollback != null) {
              onRollback.run();
            }
          }
        });
  }

  private static Pattern tmpKeyPattern(long productId) {
    return Pattern.compile("^products/" + productId + "/tmp/[0-9a-f-]{36}\\.(jpg|png|webp)$");
  }

  private static String normalize(String contentType) {
    return contentType.trim().toLowerCase(Locale.ROOT);
  }

  private static ProductImageResponse toDto(ProductImage i) {
    return new ProductImageResponse(i.getId(), i.getUrl(), i.getSortOrder());
  }

  private static BusinessException productNotFound() {
    return new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found");
  }

  private static BusinessException limitExceeded() {
    return new BusinessException(
        ErrorCode.IMAGE_LIMIT_EXCEEDED, "A product can have at most " + MAX_IMAGES + " images");
  }

  private static BusinessException unsupportedType() {
    return new BusinessException(
        ErrorCode.UNSUPPORTED_MEDIA_TYPE,
        HttpStatus.BAD_REQUEST,
        "Only JPEG, PNG and WebP images are allowed");
  }

  private static BusinessException tooLarge() {
    return new BusinessException(
        ErrorCode.FILE_TOO_LARGE, HttpStatus.BAD_REQUEST, "Image must be at most 5 MB");
  }
}
