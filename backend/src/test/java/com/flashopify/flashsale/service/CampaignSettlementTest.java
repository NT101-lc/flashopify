package com.flashopify.flashsale.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.flashsale.domain.Campaign;
import com.flashopify.flashsale.domain.CampaignItem;
import com.flashopify.flashsale.domain.CampaignStatus;
import com.flashopify.flashsale.mapper.CampaignMapper;
import com.flashopify.flashsale.mapper.FlashSaleItemMapper;
import com.flashopify.inventory.service.InventoryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignSettlementTest {

  private static final long CAMPAIGN_ID = 7L;

  @Mock CampaignMapper campaignMapper;
  @Mock FlashSaleItemMapper itemMapper;
  @Mock InventoryService inventoryService;
  @Mock FlashSaleStockStore stockStore;
  @InjectMocks CampaignSettlement settlement;

  private static Campaign campaign(boolean warmedUp) {
    return Campaign.builder()
        .id(CAMPAIGN_ID)
        .status(CampaignStatus.RUNNING)
        .warmedUpAt(warmedUp ? Instant.parse("2026-11-20T10:00:00Z") : null)
        .build();
  }

  private static CampaignItem item(long id, long productId, int totalStock, int sold) {
    return CampaignItem.builder()
        .id(id)
        .campaignId(CAMPAIGN_ID)
        .productId(productId)
        .salePrice(BigDecimal.ONE)
        .totalStock(totalStock)
        .sold(sold)
        .limitPerUser(1)
        .build();
  }

  @Test
  void soldComesFromTheFrozenRedisStockAndOnlyUnsoldIsReleased() {
    List<CampaignItem> items = List.of(item(11, 1, 10, 0), item(12, 2, 5, 0));
    when(stockStore.deactivate(11)).thenReturn(OptionalInt.of(7));
    when(stockStore.deactivate(12)).thenReturn(OptionalInt.of(0));

    settlement.close(campaign(true), items, CampaignStatus.ENDED);

    InOrder order = inOrder(stockStore, itemMapper, campaignMapper, inventoryService);
    order.verify(stockStore).deactivate(11);
    order.verify(stockStore).deactivate(12);
    order.verify(itemMapper).updateSold(11, 3);
    order.verify(itemMapper).updateSold(12, 5);
    order.verify(campaignMapper).updateStatus(CAMPAIGN_ID, CampaignStatus.ENDED);
    order.verify(inventoryService).release(1L, 7);
    verify(inventoryService, never()).release(2L, 0);
  }

  @Test
  void neverLoadedItemsKeepTheirDbSoldAndReleaseTheRest() {
    when(stockStore.deactivate(11)).thenReturn(OptionalInt.empty());

    settlement.close(campaign(false), List.of(item(11, 1, 10, 4)), CampaignStatus.CANCELLED);

    verify(itemMapper, never()).updateSold(anyLong(), anyInt());
    verify(campaignMapper).updateStatus(CAMPAIGN_ID, CampaignStatus.CANCELLED);
    verify(inventoryService).release(1L, 6);
  }

  @Test
  void liveKeysLeftByAFailedWarmUpCommitAreStillDeactivatedAndCounted() {
    when(stockStore.deactivate(11)).thenReturn(OptionalInt.of(8));

    settlement.close(campaign(false), List.of(item(11, 1, 10, 0)), CampaignStatus.CANCELLED);

    verify(itemMapper).updateSold(11, 2);
    verify(inventoryService).release(1L, 8);
  }

  @Test
  void missingRedisStockOfAWarmedUpCampaignFailsWithoutAnyWrite() {
    when(stockStore.deactivate(11)).thenReturn(OptionalInt.empty());

    assertThatThrownBy(
            () ->
                settlement.close(campaign(true), List.of(item(11, 1, 10, 0)), CampaignStatus.ENDED))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    verifyNoInteractions(itemMapper, campaignMapper, inventoryService);
  }

  @Test
  void redisStockAboveTotalIsClampedToZeroSold() {
    when(stockStore.deactivate(11)).thenReturn(OptionalInt.of(12));

    settlement.close(campaign(true), List.of(item(11, 1, 10, 0)), CampaignStatus.ENDED);

    verify(itemMapper, never()).updateSold(anyLong(), anyInt());
    verify(inventoryService).release(1L, 10);
  }
}
