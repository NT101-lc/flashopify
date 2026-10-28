package com.flashopify.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.flashsale.service.FlashSaleQueryService;
import com.flashopify.inventory.service.InventoryService;
import com.flashopify.product.domain.Product;
import com.flashopify.product.domain.ProductChangedEvent;
import com.flashopify.product.domain.ProductStatus;
import com.flashopify.product.dto.ProductUpsertRequest;
import com.flashopify.product.mapper.CategoryMapper;
import com.flashopify.product.mapper.ProductImageMapper;
import com.flashopify.product.mapper.ProductMapper;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class AdminProductServiceImplTest {

  @Mock ProductMapper productMapper;
  @Mock CategoryMapper categoryMapper;
  @Mock ProductImageMapper imageMapper;
  @Mock InventoryService inventoryService;
  @Mock FlashSaleQueryService flashSaleQueryService;
  @Mock ApplicationEventPublisher events;
  @InjectMocks AdminProductServiceImpl service;

  private static ProductUpsertRequest request(String slug) {
    return new ProductUpsertRequest(
        3L, "SKU-1", "Name", slug, null, null, new BigDecimal("9.99"), "ACTIVE", 5);
  }

  private static Product existing(ProductStatus status) {
    return Product.builder().id(7L).categoryId(3L).slug("old").status(status).build();
  }

  private static ErrorCode codeOf(Runnable call) {
    try {
      call.run();
    } catch (BusinessException e) {
      return e.getCode();
    }
    throw new AssertionError("expected BusinessException");
  }

  @Test
  void createWithUnknownCategoryWritesNothing() {
    when(categoryMapper.existsById(3L)).thenReturn(false);
    assertThat(codeOf(() -> service.create(request("s")))).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
    verifyNoInteractions(inventoryService);
    verify(productMapper, never()).insert(any());
  }

  @Test
  void duplicateKeyIsMappedBySchemaKeyName() {
    when(categoryMapper.existsById(3L)).thenReturn(true);
    when(productMapper.insert(any()))
        .thenThrow(
            new DuplicateKeyException("Duplicate entry 'x' for key 'products.uk_products_sku'"))
        .thenThrow(
            new DuplicateKeyException("Duplicate entry 'x' for key 'products.uk_products_slug'"));
    assertThat(codeOf(() -> service.create(request("s")))).isEqualTo(ErrorCode.DUPLICATE_SKU);
    assertThat(codeOf(() -> service.create(request("s")))).isEqualTo(ErrorCode.DUPLICATE_SLUG);
    verifyNoInteractions(inventoryService);
  }

  @Test
  void updatePublishesOldAndNewSlug() {
    when(productMapper.lockById(7L)).thenReturn(existing(ProductStatus.ACTIVE));
    when(categoryMapper.existsById(3L)).thenReturn(true);
    when(productMapper.findById(7L)).thenReturn(existing(ProductStatus.ACTIVE));
    service.update(7L, request("new"));
    ArgumentCaptor<ProductChangedEvent> captor = ArgumentCaptor.forClass(ProductChangedEvent.class);
    verify(events).publishEvent(captor.capture());
    assertThat(captor.getValue().slugs()).isEqualTo(Set.of("old", "new"));
  }

  @Test
  void updateOfDeletedProductIsNotFound() {
    when(productMapper.lockById(7L)).thenReturn(existing(ProductStatus.DELETED));
    assertThat(codeOf(() -> service.update(7L, request("s"))))
        .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
  }

  @Test
  void deleteInActiveCampaignIsRejected() {
    when(productMapper.lockById(7L)).thenReturn(existing(ProductStatus.ACTIVE));
    when(flashSaleQueryService.isProductInActiveCampaign(7L)).thenReturn(true);
    assertThat(codeOf(() -> service.delete(7L))).isEqualTo(ErrorCode.PRODUCT_IN_ACTIVE_CAMPAIGN);
    verify(productMapper, never()).softDelete(7L);
    verifyNoInteractions(events);
  }

  @Test
  void deleteOfDeletedProductIsNoOp() {
    when(productMapper.lockById(7L)).thenReturn(existing(ProductStatus.DELETED));
    service.delete(7L);
    verify(productMapper, never()).softDelete(7L);
    verifyNoInteractions(events, flashSaleQueryService);
  }

  @Test
  void deleteSoftDeletesAndPublishesEvent() {
    when(productMapper.lockById(7L)).thenReturn(existing(ProductStatus.ACTIVE));
    service.delete(7L);
    verify(productMapper).softDelete(7L);
    verify(events).publishEvent(new ProductChangedEvent(7L, Set.of("old")));
  }

  @Test
  void tc22UpdateLocksProductBeforeCategoryAndCampaignChecks() {
    when(productMapper.lockById(7L)).thenReturn(existing(ProductStatus.ACTIVE));
    when(categoryMapper.existsById(3L)).thenReturn(true);
    when(productMapper.findById(7L)).thenReturn(existing(ProductStatus.INACTIVE));
    ProductUpsertRequest toInactive =
        new ProductUpsertRequest(
            3L, "SKU-1", "Name", "old", null, null, new BigDecimal("9.99"), "INACTIVE", 5);

    service.update(7L, toInactive);

    InOrder order = inOrder(productMapper, categoryMapper, flashSaleQueryService);
    order.verify(productMapper).lockById(7L);
    order.verify(categoryMapper).existsById(3L);
    order.verify(flashSaleQueryService).isProductInActiveCampaign(7L);
    order.verify(productMapper).update(any());
  }

  @Test
  void tc22DeleteLocksProductBeforeCampaignCheck() {
    when(productMapper.lockById(7L)).thenReturn(existing(ProductStatus.ACTIVE));

    service.delete(7L);

    InOrder order = inOrder(productMapper, flashSaleQueryService);
    order.verify(productMapper).lockById(7L);
    order.verify(flashSaleQueryService).isProductInActiveCampaign(7L);
    order.verify(productMapper).softDelete(7L);
  }
}
