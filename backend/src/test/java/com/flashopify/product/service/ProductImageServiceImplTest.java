package com.flashopify.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductImageServiceImplTest {

  private static final long PID = 7L;
  private static final String BASE = "http://cdn.test/media/";
  private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
  private static final String UUID_RE =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @Mock ProductMapper productMapper;
  @Mock ProductImageMapper imageMapper;
  @Mock ObjectStorage storage;
  @Mock ApplicationEventPublisher events;

  private ProductImageServiceImpl service;
  private Product product;

  @BeforeEach
  void setUp() {
    StorageProperties props =
        new StorageProperties(
            "b", "us-east-1", null, null, true, "k", "s", "http://cdn.test/media", null);
    service =
        new ProductImageServiceImpl(
            productMapper, imageMapper, storage, props, events, Clock.fixed(NOW, ZoneOffset.UTC));
    product = Product.builder().id(PID).slug("slug-7").status(ProductStatus.ACTIVE).build();
    when(productMapper.findById(PID)).thenReturn(product);
    when(productMapper.lockById(PID)).thenReturn(product);
    doAnswer(
            i -> {
              i.<ProductImage>getArgument(0).setId(1L);
              return 1;
            })
        .when(imageMapper)
        .insert(any());
    when(storage.publicUrl(anyString())).thenAnswer(i -> BASE + i.getArgument(0));
    when(storage.presignPut(anyString(), anyString(), anyLong(), any()))
        .thenAnswer(
            i ->
                new PresignedUpload(
                    "http://up/" + i.getArgument(0),
                    Map.of("Content-Type", i.<String>getArgument(1)),
                    Instant.EPOCH));
  }

  private static String tmpKey(long pid, String ext) {
    return "products/" + pid + "/tmp/" + UUID.randomUUID() + "." + ext;
  }

  private ImageUploadUrlResponse upload(String file, String type, long len) {
    return service.createUploadUrl(PID, new ImageUploadUrlRequest(file, type, len));
  }

  private static ProductImage img(long id, int order) {
    return ProductImage.builder()
        .id(id)
        .productId(PID)
        .url(BASE + "products/7/" + id + ".webp")
        .sortOrder(order)
        .build();
  }

  // ---- TC-1 ----

  @Test
  void tc1KeyMatchesTmpPatternExtFromContentTypeAndFileNameIgnored() {
    ImageUploadUrlResponse r = upload("../../x.php", "image/png", 1000);
    assertThat(r.objectKey()).matches("products/7/tmp/" + UUID_RE + "\\.png");
    assertThat(r.objectKey()).doesNotContain("x.php").doesNotContain("..");
    assertThat(r.method()).isEqualTo("PUT");
    assertThat(r.headers()).containsOnlyKeys("Content-Type");
    assertThat(r.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    assertThat(upload("a", "image/jpeg", 1).objectKey()).endsWith(".jpg");
    assertThat(upload("a", "image/webp", 1).objectKey()).endsWith(".webp");
    verify(storage)
        .presignPut(eq(r.objectKey()), eq("image/png"), eq(1000L), eq(Duration.ofMinutes(10)));
  }

  // ---- TC-2 ----

  @Test
  void tc2UnsupportedTypeAndTooLargeAreRejectedButExactLimitPasses() {
    assertThatThrownBy(() -> upload("a.gif", "image/gif", 10))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
              assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
    assertThatThrownBy(() -> upload("a.png", "image/png", 5_242_881L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE);
              assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
    assertThatCode(() -> upload("a.png", "image/png", 5_242_880L)).doesNotThrowAnyException();
  }

  // ---- TC-3 / TC-15 ----

  @Test
  void tc3UploadUrlRejectedWhenProductAlreadyHasEightImages() {
    when(imageMapper.countByProductId(PID)).thenReturn(8);
    assertThatThrownBy(() -> upload("a.png", "image/png", 10))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ErrorCode.IMAGE_LIMIT_EXCEEDED);
              assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
    verify(storage, never()).presignPut(anyString(), anyString(), anyLong(), any());
  }

  @Test
  void tc3AddImageRejectedWhenProductAlreadyHasEightImages() {
    when(imageMapper.countByProductId(PID)).thenReturn(8);
    AddProductImageRequest req = new AddProductImageRequest(tmpKey(PID, "png"), null, false);
    assertThatThrownBy(() -> service.addImage(PID, req))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IMAGE_LIMIT_EXCEEDED));
    verify(storage, never()).head(anyString());
  }

  @Test
  void tc15UnknownOrDeletedProductIsNotFoundOnEveryOperation() {
    product.setStatus(ProductStatus.DELETED);
    when(productMapper.findById(99L)).thenReturn(null);
    when(productMapper.lockById(99L)).thenReturn(null);
    List<Runnable> ops =
        List.of(
            () -> service.createUploadUrl(PID, new ImageUploadUrlRequest("a", "image/png", 1L)),
            () -> service.addImage(PID, new AddProductImageRequest(tmpKey(PID, "png"), 0, false)),
            () -> service.reorder(PID, new ReorderImagesRequest(List.of())),
            () -> service.deleteImage(PID, 1),
            () -> service.createUploadUrl(99, new ImageUploadUrlRequest("a", "image/png", 1L)),
            () -> service.deleteImage(99, 1));
    for (Runnable op : ops) {
      assertThatThrownBy(op::run)
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }
    verifyNoInteractions(storage);
  }

  // ---- TC-5 / TC-6 / TC-7 ----

  @Test
  void tc5ValidKeyNeverUploadedIsObjectNotFoundAndNothingIsWritten() {
    String key = tmpKey(PID, "webp");
    when(storage.head(key)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.addImage(PID, new AddProductImageRequest(key, null, false)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ErrorCode.OBJECT_NOT_FOUND);
              assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
    verify(imageMapper, never()).insert(any());
    verify(storage, never()).copy(anyString(), anyString());
  }

  @Test
  void tc6ForeignOrMalformedKeysAreValidationErrorsWithoutAnyStorageCall() {
    String uuid = UUID.randomUUID().toString();
    List<String> bad =
        List.of(
            tmpKey(8, "png"),
            "products/7/" + uuid + ".png",
            "products/7/tmp/../8/tmp/" + uuid + ".png",
            "products/7/tmp/" + uuid + ".gif",
            "products/7/tmp/" + uuid + ".png/../../x",
            "products/70/tmp/" + uuid + ".png",
            "/products/7/tmp/" + uuid + ".png",
            "products/7/tmp/short.png",
            "other/7/tmp/" + uuid + ".png");
    for (String key : bad) {
      assertThatThrownBy(() -> service.addImage(PID, new AddProductImageRequest(key, null, false)))
          .as(key)
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
    verify(storage, never()).head(anyString());
    verify(storage, never()).copy(anyString(), anyString());
    verify(storage, never()).delete(anyString());
  }

  @Test
  void tc7OversizedObjectIsDeletedAndRejected() {
    String key = tmpKey(PID, "png");
    when(storage.head(key))
        .thenReturn(Optional.of(new ObjectMetadata(6L * 1024 * 1024, "image/png")));
    assertThatThrownBy(() -> service.addImage(PID, new AddProductImageRequest(key, null, false)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE);
              assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
    verify(storage).delete(key);
    verify(storage, never()).copy(anyString(), anyString());
    verify(imageMapper, never()).insert(any());
  }

  @Test
  void tc7WrongContentTypeObjectIsDeletedAndRejected() {
    String key = tmpKey(PID, "png");
    when(storage.head(key)).thenReturn(Optional.of(new ObjectMetadata(10, "text/html")));
    assertThatThrownBy(() -> service.addImage(PID, new AddProductImageRequest(key, null, false)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    verify(storage).delete(key);
    verify(imageMapper, never()).insert(any());
  }

  // ---- TC-9 ----

  @Test
  void tc9AddCopiesToFinalKeyInsertsAtEndAndFirstImageBecomesThumbnail() {
    String key = tmpKey(PID, "png");
    when(storage.head(key)).thenReturn(Optional.of(new ObjectMetadata(100, "image/webp")));
    when(imageMapper.countByProductId(PID)).thenReturn(2);
    doAnswer(
            i -> {
              i.<ProductImage>getArgument(0).setId(55L);
              return 1;
            })
        .when(imageMapper)
        .insert(any());

    ProductImageResponse r = service.addImage(PID, new AddProductImageRequest(key, null, false));

    ArgumentCaptor<String> finalKey = ArgumentCaptor.forClass(String.class);
    verify(storage).copy(eq(key), finalKey.capture());
    assertThat(finalKey.getValue()).matches("products/7/" + UUID_RE + "\\.webp");
    assertThat(r.id()).isEqualTo(55L);
    assertThat(r.sortOrder()).isEqualTo(2);
    assertThat(r.url()).isEqualTo(BASE + finalKey.getValue());
    verify(imageMapper, never()).shiftFrom(anyLong(), anyInt());
    verify(productMapper).updateThumbnail(PID, r.url());
    verify(storage).delete(key); // no transaction in a unit test: runs immediately
    ArgumentCaptor<ProductChangedEvent> ev = ArgumentCaptor.forClass(ProductChangedEvent.class);
    verify(events).publishEvent(ev.capture());
    assertThat(ev.getValue().slugs()).containsExactly("slug-7");
  }

  @Test
  void tc9ExplicitSortOrderShiftsExistingAndIsClamped() {
    String key = tmpKey(PID, "png");
    when(storage.head(key)).thenReturn(Optional.of(new ObjectMetadata(100, "image/png")));
    when(imageMapper.countByProductId(PID)).thenReturn(3);
    product.setThumbnailUrl("http://existing");

    ProductImageResponse first = service.addImage(PID, new AddProductImageRequest(key, 0, false));
    assertThat(first.sortOrder()).isZero();
    verify(imageMapper).shiftFrom(PID, 0);
    verify(productMapper, never()).updateThumbnail(anyLong(), any());

    ProductImageResponse clamped = service.addImage(PID, new AddProductImageRequest(key, 99, true));
    assertThat(clamped.sortOrder()).isEqualTo(3);
    verify(productMapper).updateThumbnail(PID, clamped.url());
  }

  // ---- TC-10 ----

  @Test
  void tc10ReorderRequiresExactlyTheFullIdSet() {
    when(imageMapper.findByProductId(PID)).thenReturn(List.of(img(1, 0), img(2, 1), img(3, 2)));
    for (List<Long> bad :
        List.of(
            List.of(1L, 2L), List.of(1L, 2L, 3L, 4L), List.of(1L, 1L, 2L), List.of(1L, 2L, 9L))) {
      assertThatThrownBy(() -> service.reorder(PID, new ReorderImagesRequest(bad)))
          .as(bad.toString())
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
    verify(imageMapper, never()).updateSortOrder(anyLong(), anyLong(), anyInt());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void tc10ReorderWritesIndexAsSortOrder() {
    when(imageMapper.findByProductId(PID)).thenReturn(List.of(img(1, 0), img(2, 1), img(3, 2)));
    service.reorder(PID, new ReorderImagesRequest(List.of(3L, 1L, 2L)));
    verify(imageMapper).updateSortOrder(3L, PID, 0);
    verify(imageMapper).updateSortOrder(1L, PID, 1);
    verify(imageMapper).updateSortOrder(2L, PID, 2);
    verify(events).publishEvent(any(ProductChangedEvent.class));
  }

  // ---- TC-11 ----

  @Test
  void tc11DeleteCompactsRepairsThumbnailAndRemovesObject() {
    ProductImage a = img(1, 0);
    ProductImage b = img(2, 1);
    ProductImage c = img(3, 2);
    product.setThumbnailUrl(a.getUrl());
    when(imageMapper.findByIdAndProduct(1L, PID)).thenReturn(a);
    when(imageMapper.findByProductId(PID)).thenReturn(new ArrayList<>(List.of(b, c)));

    service.deleteImage(PID, 1);

    verify(imageMapper).deleteByIdAndProduct(1L, PID);
    verify(imageMapper).updateSortOrder(2L, PID, 0);
    verify(imageMapper).updateSortOrder(3L, PID, 1);
    verify(productMapper).updateThumbnail(PID, b.getUrl());
    verify(storage).delete("products/7/1.webp");
    verify(events).publishEvent(any(ProductChangedEvent.class));
  }

  @Test
  void tc11DeletingLastThumbnailImageClearsThumbnail() {
    ProductImage a = img(1, 0);
    product.setThumbnailUrl(a.getUrl());
    when(imageMapper.findByIdAndProduct(1L, PID)).thenReturn(a);
    when(imageMapper.findByProductId(PID)).thenReturn(List.of());
    service.deleteImage(PID, 1);
    verify(productMapper).updateThumbnail(PID, null);
  }

  @Test
  void tc11ImageOfAnotherProductIsImageNotFound() {
    when(imageMapper.findByIdAndProduct(5L, PID)).thenReturn(null);
    assertThatThrownBy(() -> service.deleteImage(PID, 5))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IMAGE_NOT_FOUND));
    verify(imageMapper, never()).deleteByIdAndProduct(anyLong(), anyLong());
    verifyNoInteractions(storage);
  }

  @Test
  void tc11StorageFailureOnDeleteIsSwallowed() {
    ProductImage a = img(1, 0);
    when(imageMapper.findByIdAndProduct(1L, PID)).thenReturn(a);
    when(imageMapper.findByProductId(PID)).thenReturn(List.of());
    doThrow(new RuntimeException("boom")).when(storage).delete(anyString());
    assertThatCode(() -> service.deleteImage(PID, 1)).doesNotThrowAnyException();
    verify(storage).delete("products/7/1.webp");
  }

  @Test
  void tc11ForeignUrlsAreNeverDeletedFromStorage() {
    ProductImage external =
        ProductImage.builder().id(1L).productId(PID).url("https://dummyjson.com/a.png").build();
    when(imageMapper.findByIdAndProduct(1L, PID)).thenReturn(external);
    when(imageMapper.findByProductId(PID)).thenReturn(List.of());
    service.deleteImage(PID, 1);
    verify(storage, never()).delete(anyString());
  }
}
