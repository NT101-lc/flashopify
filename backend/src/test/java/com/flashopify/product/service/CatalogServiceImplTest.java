package com.flashopify.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.flashopify.product.dto.CategoryResponse;
import com.flashopify.product.dto.ProductDetailResponse;
import com.flashopify.product.dto.ProductSummaryResponse;
import com.flashopify.product.mapper.CategoryMapper;
import com.flashopify.product.mapper.ProductImageMapper;
import com.flashopify.product.mapper.ProductMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class CatalogServiceImplTest {

  @Mock ProductMapper productMapper;
  @Mock CategoryMapper categoryMapper;
  @Mock ProductImageMapper imageMapper;
  @Mock InventoryService inventoryService;
  @Mock StringRedisTemplate redis;
  @Mock ValueOperations<String, String> valueOps;

  CatalogServiceImpl service;

  @BeforeEach
  void setUp() {
    ProductDetailCache cache =
        new ProductDetailCache(
            redis,
            new ObjectMapper(),
            new CatalogProperties(new CatalogProperties.Cache(Duration.ofMinutes(10))));
    service =
        new CatalogServiceImpl(productMapper, categoryMapper, imageMapper, inventoryService, cache);
  }

  private static Product product(long id) {
    return Product.builder()
        .id(id)
        .categoryId(7L)
        .categorySlug("phones")
        .categoryName("Phones")
        .sku("SKU-" + id)
        .name("P" + id)
        .slug("p-" + id)
        .price(new BigDecimal("10.5"))
        .build();
  }

  private ProductSearchCriteria searchCriteria(String q, String sort, int page, int size) {
    when(productMapper.countActive(any())).thenReturn(1L);
    service.search(q, null, null, null, null, null, sort, page, size);
    ArgumentCaptor<ProductSearchCriteria> captor =
        ArgumentCaptor.forClass(ProductSearchCriteria.class);
    verify(productMapper).countActive(captor.capture());
    return captor.getValue();
  }

  // ---- TC-2 ----

  @ParameterizedTest
  @EnumSource(ProductSort.class)
  void everySortIsPassedToCriteria(ProductSort sort) {
    assertThat(searchCriteria(null, sort.getApiValue(), 0, 20).getSort()).isEqualTo(sort);
  }

  @Test
  void nullSortUsesDefault() {
    assertThat(searchCriteria(null, null, 0, 20).getSort()).isEqualTo(ProductSort.CREATED_AT_DESC);
  }

  @Test
  void unknownSortIsValidationError() {
    assertThatThrownBy(() -> service.search(null, null, null, null, null, null, "id,desc", 0, 20))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    verifyNoInteractions(productMapper);
  }

  @Test
  void offsetIsPageTimesSize() {
    ProductSearchCriteria c = searchCriteria(null, null, 3, 20);
    assertThat(c.getOffset()).isEqualTo(60);
    assertThat(c.getLimit()).isEqualTo(20);
  }

  @Test
  void totalPagesIsZeroForEmptyResult() {
    when(productMapper.countActive(any())).thenReturn(0L);
    PageResponse<ProductSummaryResponse> r =
        service.search(null, null, null, null, null, null, null, 0, 20);
    assertThat(r.totalPages()).isZero();
    assertThat(r.totalItems()).isZero();
    assertThat(r.items()).isEmpty();
    verify(productMapper, never()).searchActive(any());
  }

  @Test
  void totalPagesRoundsUp() {
    when(productMapper.countActive(any())).thenReturn(41L);
    when(productMapper.searchActive(any())).thenReturn(List.of(product(1)));
    when(inventoryService.findByProductIds(anyCollection())).thenReturn(Map.of());
    PageResponse<ProductSummaryResponse> r =
        service.search(null, null, null, null, null, null, null, 0, 20);
    assertThat(r.totalPages()).isEqualTo(3);
    assertThat(r.totalItems()).isEqualTo(41);
    assertThat(r.page()).isZero();
    assertThat(r.size()).isEqualTo(20);
  }

  @Test
  void queryWildcardsAreEscaped() {
    assertThat(searchCriteria("50%_off", null, 0, 20).getQ()).isEqualTo("50\\%\\_off");
  }

  @Test
  void blankQueryIsIgnored() {
    assertThat(searchCriteria("   ", null, 0, 20).getQ()).isNull();
  }

  @Test
  void minPriceAboveMaxPriceIsValidationError() {
    assertThatThrownBy(
            () ->
                service.search(
                    null, null, null, new BigDecimal("10"), new BigDecimal("5"), null, null, 0, 20))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }

  @Test
  void unknownCategorySlugGivesEmptyPage() {
    when(categoryMapper.findBySlug("nope")).thenReturn(null);
    PageResponse<ProductSummaryResponse> r =
        service.search(null, "nope", null, null, null, null, null, 0, 20);
    assertThat(r.items()).isEmpty();
    assertThat(r.totalPages()).isZero();
    verifyNoInteractions(productMapper);
    assertThat(service.brands("nope")).isEmpty();
  }

  @Test
  void categorySlugIncludesDescendants() {
    when(categoryMapper.findBySlug("root"))
        .thenReturn(Category.builder().id(1L).slug("root").build());
    when(categoryMapper.findAll())
        .thenReturn(
            List.of(
                Category.builder().id(1L).slug("root").name("Root").build(),
                Category.builder().id(2L).parentId(1L).slug("kid").name("Kid").build(),
                Category.builder().id(3L).parentId(2L).slug("grandkid").name("G").build(),
                Category.builder().id(4L).slug("other").name("Other").build()));
    when(productMapper.countActive(any())).thenReturn(0L);
    service.search(null, "root", null, null, null, null, null, 0, 20);
    ArgumentCaptor<ProductSearchCriteria> captor =
        ArgumentCaptor.forClass(ProductSearchCriteria.class);
    verify(productMapper).countActive(captor.capture());
    assertThat(captor.getValue().getCategoryIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
  }

  @Test
  void stockIsLoadedWithOneBatchCall() {
    when(productMapper.countActive(any())).thenReturn(2L);
    when(productMapper.searchActive(any())).thenReturn(List.of(product(1), product(2)));
    when(inventoryService.findByProductIds(anyCollection()))
        .thenReturn(Map.of(1L, InventoryView.of(5, 5, 0), 2L, InventoryView.of(5, 1, 0)));
    PageResponse<ProductSummaryResponse> r =
        service.search(null, null, null, null, null, null, null, 0, 20);
    assertThat(r.items()).extracting(ProductSummaryResponse::inStock).containsExactly(false, true);
    verify(inventoryService).findByProductIds(List.of(1L, 2L));
    verify(inventoryService, never()).availableOf(anyLong());
  }

  @Test
  void categoryTreeAggregatesDescendantsAndSortsByName() {
    when(categoryMapper.findAll())
        .thenReturn(
            List.of(
                Category.builder().id(1L).slug("z").name("Zed").build(),
                Category.builder().id(2L).parentId(1L).slug("b").name("B").build(),
                Category.builder().id(3L).parentId(1L).slug("a").name("A").build(),
                Category.builder().id(4L).slug("m").name("Mid").build()));
    when(categoryMapper.countActiveProductsByCategory())
        .thenReturn(
            List.of(
                new CategoryProductCount(1L, 1),
                new CategoryProductCount(2L, 2),
                new CategoryProductCount(3L, 3)));
    List<CategoryResponse> tree = service.categoryTree();
    assertThat(tree).extracting(CategoryResponse::slug).containsExactly("m", "z");
    CategoryResponse z = tree.get(1);
    assertThat(z.productCount()).isEqualTo(6);
    assertThat(z.children()).extracting(CategoryResponse::slug).containsExactly("a", "b");
    assertThat(tree.get(0).productCount()).isZero();
  }

  // ---- TC-3 ----

  private void redisOps() {
    when(redis.opsForValue()).thenReturn(valueOps);
  }

  @Test
  void cacheMissLoadsFromDbAndPuts() {
    redisOps();
    when(valueOps.get("product:detail:p-1")).thenReturn(null);
    when(productMapper.findActiveBySlug("p-1")).thenReturn(product(1));
    when(imageMapper.findByProductId(1L))
        .thenReturn(List.of(ProductImage.builder().id(9L).url("u").sortOrder(0).build()));
    when(inventoryService.availableOf(1L)).thenReturn(4);

    ProductDetailResponse r = service.getBySlug("p-1");

    assertThat(r.availableQuantity()).isEqualTo(4);
    assertThat(r.inStock()).isTrue();
    assertThat(r.images()).hasSize(1);
    verify(valueOps).set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void cacheHitSkipsProductMapperButOverlaysLiveStock() throws Exception {
    redisOps();
    String json =
        new ObjectMapper()
            .writeValueAsString(
                new ProductDetailResponse(
                    1,
                    "P1",
                    "p-1",
                    null,
                    BigDecimal.TEN,
                    null,
                    null,
                    "phones",
                    false,
                    null,
                    "S",
                    null,
                    null,
                    0,
                    List.of()));
    when(valueOps.get("product:detail:p-1")).thenReturn(json);
    when(inventoryService.availableOf(1L)).thenReturn(7);

    ProductDetailResponse r = service.getBySlug("p-1");

    assertThat(r.availableQuantity()).isEqualTo(7);
    assertThat(r.inStock()).isTrue();
    verifyNoInteractions(productMapper, imageMapper);
    verify(inventoryService).availableOf(1L);
  }

  @Test
  void redisFailureFallsBackToDb() {
    redisOps();
    when(valueOps.get(anyString())).thenThrow(new QueryTimeoutException("down"));
    org.mockito.Mockito.doThrow(new QueryTimeoutException("down"))
        .when(valueOps)
        .set(anyString(), anyString(), any(Duration.class));
    when(productMapper.findActiveBySlug("p-1")).thenReturn(product(1));
    when(imageMapper.findByProductId(1L)).thenReturn(List.of());
    when(inventoryService.availableOf(1L)).thenReturn(0);

    ProductDetailResponse r = service.getBySlug("p-1");

    assertThat(r.id()).isEqualTo(1);
    assertThat(r.inStock()).isFalse();
  }

  @Test
  void missingProductIsNotFoundAndNotCached() {
    redisOps();
    when(valueOps.get(anyString())).thenReturn(null);
    when(productMapper.findActiveBySlug("gone")).thenReturn(null);

    assertThatThrownBy(() -> service.getBySlug("gone"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    verifyNoInteractions(inventoryService);
  }
}
