package com.flashopify.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashopify.inventory.service.InventoryService;
import com.flashopify.product.domain.Category;
import com.flashopify.product.domain.Product;
import com.flashopify.product.domain.ProductImage;
import com.flashopify.product.domain.ProductStatus;
import com.flashopify.product.dto.ProductImportCommand;
import com.flashopify.product.mapper.CategoryMapper;
import com.flashopify.product.mapper.ProductImageMapper;
import com.flashopify.product.mapper.ProductMapper;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/** TC-4. */
class ProductImportServiceImplTest {

  private ProductMapper productMapper;
  private CategoryMapper categoryMapper;
  private ProductImageMapper imageMapper;
  private InventoryService inventory;
  private ProductImportServiceImpl service;

  @BeforeEach
  void setUp() {
    productMapper = mock(ProductMapper.class);
    categoryMapper = mock(CategoryMapper.class);
    imageMapper = mock(ProductImageMapper.class);
    inventory = mock(InventoryService.class);
    service = new ProductImportServiceImpl(productMapper, categoryMapper, imageMapper, inventory);
    // Every successful insert assigns id 55.
    doAnswer(
            i -> {
              ((Product) i.getArgument(0)).setId(55L);
              return 1;
            })
        .when(productMapper)
        .insert(any());
  }

  private static ProductImportCommand command(List<String> images) {
    return new ProductImportCommand(
        9,
        3L,
        "SKU-9",
        "Name",
        "name",
        "d",
        "b",
        new BigDecimal("1.50"),
        new BigDecimal("4.00"),
        12,
        "http://t",
        images);
  }

  private static DuplicateKeyException duplicate(String key) {
    return new DuplicateKeyException(
        "dup", new SQLException("Duplicate entry 'x' for key 'products." + key + "'"));
  }

  @Test
  void importsProductImagesAndInventory() {
    Optional<Long> id = service.importProduct(command(List.of("a", "b")));

    assertThat(id).contains(55L);
    ArgumentCaptor<Product> product = ArgumentCaptor.forClass(Product.class);
    verify(productMapper).insert(product.capture());
    assertThat(product.getValue().getStatus()).isEqualTo(ProductStatus.ACTIVE);
    assertThat(product.getValue().getSourceId()).isEqualTo(9);
    assertThat(product.getValue().getThumbnailUrl()).isEqualTo("http://t");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ProductImage>> rows = ArgumentCaptor.forClass(List.class);
    verify(imageMapper).insertBatch(rows.capture());
    assertThat(rows.getValue())
        .extracting(ProductImage::getUrl, ProductImage::getSortOrder, ProductImage::getProductId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("a", 0, 55L),
            org.assertj.core.groups.Tuple.tuple("b", 1, 55L));
    verify(inventory).initialize(55L, 12);
  }

  @Test
  void storesAtMostEightImages() {
    service.importProduct(command(IntStream.range(0, 12).mapToObj(i -> "u" + i).toList()));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ProductImage>> rows = ArgumentCaptor.forClass(List.class);
    verify(imageMapper).insertBatch(rows.capture());
    assertThat(rows.getValue()).hasSize(8);
  }

  @Test
  void noImagesMeansNoBatchInsert() {
    service.importProduct(command(List.of()));

    verify(imageMapper, never()).insertBatch(anyList());
    verify(inventory).initialize(55L, 12);
  }

  @Test
  void duplicateSourceIdReturnsEmptyWithoutImagesOrInventory() {
    doThrow(duplicate("uk_products_source")).when(productMapper).insert(any());

    assertThat(service.importProduct(command(List.of("a")))).isEmpty();

    verify(imageMapper, never()).insertBatch(anyList());
    verify(inventory, never()).initialize(org.mockito.ArgumentMatchers.anyLong(), anyInt());
  }

  @Test
  void duplicateSlugRetriesWithSourceIdSuffix() {
    doThrow(duplicate("uk_products_slug"))
        .doAnswer(
            i -> {
              ((Product) i.getArgument(0)).setId(55L);
              return 1;
            })
        .when(productMapper)
        .insert(any());

    Optional<Long> id = service.importProduct(command(List.of()));

    assertThat(id).contains(55L);
    ArgumentCaptor<Product> product = ArgumentCaptor.forClass(Product.class);
    verify(productMapper, times(2)).insert(product.capture());
    assertThat(product.getValue().getSlug()).isEqualTo("name-9");
    verify(inventory).initialize(55L, 12);
  }

  @Test
  void duplicateSkuRetriesWithGeneratedSku() {
    doThrow(duplicate("uk_products_sku"))
        .doAnswer(
            i -> {
              ((Product) i.getArgument(0)).setId(55L);
              return 1;
            })
        .when(productMapper)
        .insert(any());

    service.importProduct(command(List.of()));

    ArgumentCaptor<Product> product = ArgumentCaptor.forClass(Product.class);
    verify(productMapper, times(2)).insert(product.capture());
    assertThat(product.getValue().getSku()).isEqualTo("DJ-9");
  }

  @Test
  void aSecondSlugCollisionIsNotRetriedForever() {
    doThrow(duplicate("uk_products_slug")).when(productMapper).insert(any());

    assertThatThrownBy(() -> service.importProduct(command(List.of())))
        .isInstanceOf(DuplicateKeyException.class);
    verify(productMapper, times(2)).insert(any());
  }

  @Test
  void upsertCategoryReturnsExistingWithoutInsert() {
    when(categoryMapper.findBySlug("beauty")).thenReturn(Category.builder().id(4L).build());

    assertThat(service.upsertCategory("beauty", "Beauty")).isEqualTo(4L);
    verify(categoryMapper, never()).insert(any());
  }

  @Test
  void upsertCategoryInsertsWhenMissing() {
    when(categoryMapper.findBySlug("beauty")).thenReturn(null);
    doAnswer(
            i -> {
              ((Category) i.getArgument(0)).setId(8L);
              return 1;
            })
        .when(categoryMapper)
        .insert(any());

    assertThat(service.upsertCategory("beauty", "Beauty")).isEqualTo(8L);
  }

  @Test
  void upsertCategoryReselectsOnDuplicate() {
    when(categoryMapper.findBySlug("beauty"))
        .thenReturn(null)
        .thenReturn(Category.builder().id(6L).build());
    doThrow(duplicate("uk_categories_slug")).when(categoryMapper).insert(any());

    assertThat(service.upsertCategory("beauty", "Beauty")).isEqualTo(6L);
  }

  @Test
  void countsExternalUrlsAcrossThumbnailsAndGallery() {
    when(productMapper.countByThumbnailContaining("h")).thenReturn(2L);
    when(imageMapper.countByUrlContaining("h")).thenReturn(3L);

    assertThat(service.countExternalImageUrls("h")).isEqualTo(5L);
  }

  @Test
  void existsBySourceIdDelegates() {
    when(productMapper.existsBySourceId(9)).thenReturn(true);

    assertThat(service.existsBySourceId(9)).isTrue();
  }
}
