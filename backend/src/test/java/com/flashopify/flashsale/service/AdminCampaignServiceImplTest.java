package com.flashopify.flashsale.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.exception.ErrorResponse.FieldError;
import com.flashopify.flashsale.domain.Campaign;
import com.flashopify.flashsale.domain.CampaignItem;
import com.flashopify.flashsale.domain.CampaignStatus;
import com.flashopify.flashsale.dto.CampaignItemRequest;
import com.flashopify.flashsale.dto.CampaignUpsertRequest;
import com.flashopify.flashsale.mapper.CampaignMapper;
import com.flashopify.flashsale.mapper.FlashSaleItemMapper;
import com.flashopify.inventory.service.InventoryService;
import com.flashopify.product.domain.ProductStatus;
import com.flashopify.product.dto.ProductSnapshot;
import com.flashopify.product.service.ProductQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminCampaignServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
  private static final Instant START = NOW.plus(Duration.ofDays(1));
  private static final Instant END = START.plus(Duration.ofHours(1));
  private static final long CAMPAIGN_ID = 7L;

  @Mock CampaignMapper campaignMapper;
  @Mock FlashSaleItemMapper itemMapper;
  @Mock ProductQueryService productQueryService;
  @Mock InventoryService inventoryService;

  private AdminCampaignServiceImpl service;

  @BeforeEach
  void init() {
    service =
        new AdminCampaignServiceImpl(
            campaignMapper,
            itemMapper,
            productQueryService,
            inventoryService,
            new FlashSaleProperties(Duration.ofMinutes(5)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  // ---- helpers ----

  private static CampaignItemRequest item(long productId, int totalStock) {
    return new CampaignItemRequest(productId, new BigDecimal("9.99"), totalStock, 1);
  }

  private static CampaignUpsertRequest request(
      Instant start, Instant end, CampaignItemRequest... items) {
    return new CampaignUpsertRequest("Black Friday", start, end, Arrays.asList(items));
  }

  private static ProductSnapshot product(long id, ProductStatus status) {
    return new ProductSnapshot(id, "P" + id, "p-" + id, new BigDecimal("20.00"), null, status);
  }

  private static Map<Long, ProductSnapshot> active(long... ids) {
    Map<Long, ProductSnapshot> m = new HashMap<>();
    for (long id : ids) {
      m.put(id, product(id, ProductStatus.ACTIVE));
    }
    return m;
  }

  private static Campaign campaign(CampaignStatus status, Instant start) {
    return Campaign.builder()
        .id(CAMPAIGN_ID)
        .name("c")
        .startTime(start)
        .endTime(start.plus(Duration.ofHours(1)))
        .status(status)
        .createdBy(1L)
        .createdAt(NOW)
        .build();
  }

  private static CampaignItem existing(long itemId, long productId, int totalStock, int sold) {
    return CampaignItem.builder()
        .id(itemId)
        .campaignId(CAMPAIGN_ID)
        .productId(productId)
        .salePrice(BigDecimal.ONE)
        .totalStock(totalStock)
        .sold(sold)
        .limitPerUser(1)
        .build();
  }

  /** Stubs the read-back that every successful write ends with. */
  private void stubDetail() {
    when(campaignMapper.findSummaryById(CAMPAIGN_ID))
        .thenReturn(campaign(CampaignStatus.SCHEDULED, START));
  }

  private static BusinessException businessError(Runnable call) {
    try {
      call.run();
    } catch (BusinessException e) {
      return e;
    }
    throw new AssertionError("expected BusinessException");
  }

  // ---- create: validation ----

  @Test
  void createRejectsPastStartAndEndNotAfterStartWithFieldErrorsBeforeAnyLock() {
    BusinessException e =
        businessError(
            () ->
                service.create(request(NOW.minusSeconds(1), NOW.minusSeconds(1), item(1, 5)), 1L));

    assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    assertThat(e.getStatus().value()).isEqualTo(400);
    assertThat(e.getFieldErrors())
        .extracting(FieldError::field)
        .containsExactly("startTime", "endTime");
    verifyNoInteractions(productQueryService, campaignMapper, itemMapper, inventoryService);
  }

  @Test
  void createRejectsStartEqualToNow() {
    BusinessException e = businessError(() -> service.create(request(NOW, END, item(1, 5)), 1L));
    assertThat(e.getFieldErrors()).extracting(FieldError::field).containsExactly("startTime");
  }

  @Test
  void createRejectsEndEqualToStartAfterTruncatingToSeconds() {
    BusinessException e =
        businessError(() -> service.create(request(START, START.plusMillis(400), item(1, 5)), 1L));
    assertThat(e.getFieldErrors()).extracting(FieldError::field).containsExactly("endTime");
  }

  @Test
  void createRejectsDuplicateProductsWithIndexedFieldError() {
    BusinessException e =
        businessError(() -> service.create(request(START, END, item(1, 5), item(1, 3)), 1L));
    assertThat(e.getFieldErrors())
        .extracting(FieldError::field)
        .containsExactly("items[1].productId");
    verifyNoInteractions(productQueryService);
  }

  // ---- create: products ----

  @Test
  void createWithUnknownProductIsNotFoundAndWritesNothing() {
    when(productQueryService.lockForShare(anyCollection())).thenReturn(active(1));
    BusinessException e =
        businessError(() -> service.create(request(START, END, item(1, 5), item(2, 5)), 1L));
    assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    verifyNoInteractions(campaignMapper, itemMapper, inventoryService);
  }

  @Test
  void createWithInactiveProductIsNotAvailableAndWritesNothing() {
    when(productQueryService.lockForShare(anyCollection()))
        .thenReturn(Map.of(1L, product(1, ProductStatus.INACTIVE)));
    BusinessException e = businessError(() -> service.create(request(START, END, item(1, 5)), 1L));
    assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);
    verifyNoInteractions(campaignMapper, itemMapper, inventoryService);
  }

  @Test
  void createLocksProductsThenWritesCampaignAndItemsThenReservesInAscendingProductOrder() {
    when(productQueryService.lockForShare(anyCollection())).thenReturn(active(3, 9));
    when(campaignMapper.insert(any()))
        .thenAnswer(
            inv -> {
              inv.<Campaign>getArgument(0).setId(CAMPAIGN_ID);
              return 1;
            });
    stubDetail();

    service.create(request(START, END, item(9, 4), item(3, 6)), 42L);

    InOrder order = inOrder(productQueryService, campaignMapper, itemMapper, inventoryService);
    order.verify(productQueryService).lockForShare(Set.of(3L, 9L));
    ArgumentCaptor<Campaign> inserted = ArgumentCaptor.forClass(Campaign.class);
    order.verify(campaignMapper).insert(inserted.capture());
    order.verify(itemMapper, org.mockito.Mockito.times(2)).insert(any());
    order.verify(inventoryService).reserve(3L, 6);
    order.verify(inventoryService).reserve(9L, 4);
    assertThat(inserted.getValue().getCreatedBy()).isEqualTo(42L);
    assertThat(inserted.getValue().getStartTime()).isEqualTo(START);
  }

  // ---- update ----

  @Test
  void updateOfUnknownCampaignIsNotFound() {
    when(itemMapper.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
    when(productQueryService.lockForShare(anyCollection())).thenReturn(active(1));
    when(campaignMapper.lockById(CAMPAIGN_ID)).thenReturn(null);
    assertThat(
            businessError(() -> service.update(CAMPAIGN_ID, request(START, END, item(1, 5))))
                .getCode())
        .isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND);
  }

  @Test
  void campaignIsEditableOnlyWhileScheduledAndStrictlyBeforeWarmUp() {
    Duration lead = Duration.ofMinutes(5);
    assertThat(
            service.isEditable(campaign(CampaignStatus.SCHEDULED, NOW.plus(lead).plusSeconds(1))))
        .isTrue();
    assertThat(service.isEditable(campaign(CampaignStatus.SCHEDULED, NOW.plus(lead)))).isFalse();
    assertThat(service.isEditable(campaign(CampaignStatus.RUNNING, START))).isFalse();
    assertThat(service.isEditable(campaign(CampaignStatus.CANCELLED, START))).isFalse();
  }

  @Test
  void updateAfterWarmUpIsNotEditableAndChangesNothing() {
    when(itemMapper.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(existing(1, 1, 5, 0)));
    when(productQueryService.lockForShare(anyCollection())).thenReturn(active(1));
    when(campaignMapper.lockById(CAMPAIGN_ID))
        .thenReturn(campaign(CampaignStatus.SCHEDULED, NOW.plus(Duration.ofMinutes(4))));

    assertThat(
            businessError(() -> service.update(CAMPAIGN_ID, request(START, END, item(1, 5))))
                .getCode())
        .isEqualTo(ErrorCode.CAMPAIGN_NOT_EDITABLE);
    verify(campaignMapper, never()).updateHeader(any());
    verifyNoInteractions(inventoryService);
  }

  @Test
  void updateAppliesReservationDeltasInAscendingProductOrder() {
    // Before: p1=10, p2=5. After: p2=8, p3=4.
    List<CampaignItem> before = List.of(existing(11, 1, 10, 0), existing(12, 2, 5, 0));
    when(itemMapper.findByCampaignId(CAMPAIGN_ID)).thenReturn(before);
    when(productQueryService.lockForShare(anyCollection())).thenReturn(active(1, 2, 3));
    when(campaignMapper.lockById(CAMPAIGN_ID))
        .thenReturn(campaign(CampaignStatus.SCHEDULED, START));
    when(itemMapper.lockByCampaignId(CAMPAIGN_ID)).thenReturn(before);
    stubDetail();

    service.update(CAMPAIGN_ID, request(START, END, item(3, 4), item(2, 8)));

    verify(productQueryService).lockForShare(Set.of(1L, 2L, 3L));
    verify(itemMapper).deleteById(11L);
    ArgumentCaptor<CampaignItem> updated = ArgumentCaptor.forClass(CampaignItem.class);
    verify(itemMapper).update(updated.capture());
    assertThat(updated.getValue().getId()).isEqualTo(12L);
    assertThat(updated.getValue().getTotalStock()).isEqualTo(8);
    InOrder order = inOrder(inventoryService);
    order.verify(inventoryService).release(1L, 10);
    order.verify(inventoryService).reserve(2L, 3);
    order.verify(inventoryService).reserve(3L, 4);
  }

  @Test
  void updateWithUnchangedStockDoesNotTouchInventory() {
    List<CampaignItem> before = List.of(existing(11, 1, 10, 0));
    when(itemMapper.findByCampaignId(CAMPAIGN_ID)).thenReturn(before);
    when(productQueryService.lockForShare(anyCollection())).thenReturn(active(1));
    when(campaignMapper.lockById(CAMPAIGN_ID))
        .thenReturn(campaign(CampaignStatus.SCHEDULED, START));
    when(itemMapper.lockByCampaignId(CAMPAIGN_ID)).thenReturn(before);
    stubDetail();

    service.update(CAMPAIGN_ID, request(START, END, item(1, 10)));

    verify(inventoryService, never()).reserve(anyLong(), anyInt());
    verify(inventoryService, never()).release(anyLong(), anyInt());
  }

  @Test
  void updateDetectsItemsAddedConcurrentlyForProductsItDidNotLock() {
    when(itemMapper.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(existing(11, 1, 10, 0)));
    when(productQueryService.lockForShare(anyCollection())).thenReturn(active(1));
    when(campaignMapper.lockById(CAMPAIGN_ID))
        .thenReturn(campaign(CampaignStatus.SCHEDULED, START));
    // A concurrent update committed product 5 between the plain read and the lock.
    when(itemMapper.lockByCampaignId(CAMPAIGN_ID))
        .thenReturn(List.of(existing(11, 1, 10, 0), existing(15, 5, 2, 0)));

    assertThat(
            businessError(() -> service.update(CAMPAIGN_ID, request(START, END, item(1, 10))))
                .getCode())
        .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    verify(campaignMapper, never()).updateHeader(any());
  }

  // ---- cancel ----

  @Test
  void cancelOfClosedCampaignIsNotEditable() {
    when(itemMapper.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
    when(campaignMapper.lockById(CAMPAIGN_ID)).thenReturn(campaign(CampaignStatus.ENDED, START));
    assertThat(businessError(() -> service.cancel(CAMPAIGN_ID)).getCode())
        .isEqualTo(ErrorCode.CAMPAIGN_NOT_EDITABLE);
    verify(campaignMapper, never()).updateStatus(anyLong(), any());
  }

  @Test
  void cancelReleasesOnlyUnsoldStock() {
    List<CampaignItem> items = List.of(existing(11, 1, 10, 3), existing(12, 2, 4, 4));
    when(itemMapper.findByCampaignId(CAMPAIGN_ID)).thenReturn(items);
    when(campaignMapper.lockById(CAMPAIGN_ID)).thenReturn(campaign(CampaignStatus.RUNNING, START));
    when(itemMapper.lockByCampaignId(CAMPAIGN_ID)).thenReturn(items);
    stubDetail();

    service.cancel(CAMPAIGN_ID);

    InOrder order = inOrder(productQueryService, campaignMapper, inventoryService);
    order.verify(productQueryService).lockForShare(Set.of(1L, 2L));
    order.verify(campaignMapper).lockById(CAMPAIGN_ID);
    order.verify(campaignMapper).updateStatus(CAMPAIGN_ID, CampaignStatus.CANCELLED);
    order.verify(inventoryService).release(1L, 7);
    verify(inventoryService, never()).release(2L, 0);
  }

  // ---- list / get ----

  @Test
  void listRejectsUnknownSort() {
    assertThatThrownBy(() -> service.list(null, "name,asc", 0, 20))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  void listWithNoRowsSkipsTheSearchQuery() {
    when(campaignMapper.count(any())).thenReturn(0L);
    assertThat(service.list(CampaignStatus.SCHEDULED, null, 0, 20).items()).isEmpty();
    verify(campaignMapper, never()).search(any());
  }

  @Test
  void getUnknownCampaignIsNotFound() {
    when(campaignMapper.findSummaryById(CAMPAIGN_ID)).thenReturn(null);
    assertThat(businessError(() -> service.get(CAMPAIGN_ID)).getCode())
        .isEqualTo(ErrorCode.CAMPAIGN_NOT_FOUND);
  }

  @Test
  void getComputesRemainingFromSoldAndUsesProductPriceAsOriginal() {
    stubDetail();
    when(itemMapper.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(existing(11, 1, 10, 3)));
    when(productQueryService.findByIds(List.of(1L))).thenReturn(active(1));

    var detail = service.get(CAMPAIGN_ID);

    assertThat(detail.items())
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.remainingStock()).isEqualTo(7);
              assertThat(i.sold()).isEqualTo(3);
              assertThat(i.originalPrice()).isEqualByComparingTo("20.00");
              assertThat(i.productSlug()).isEqualTo("p-1");
            });
  }
}
