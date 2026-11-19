package com.flashopify.flashsale.service;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.flashsale.domain.Campaign;
import com.flashopify.flashsale.domain.CampaignItem;
import com.flashopify.flashsale.domain.CampaignStatus;
import com.flashopify.flashsale.mapper.CampaignMapper;
import com.flashopify.flashsale.mapper.FlashSaleItemMapper;
import com.flashopify.inventory.service.InventoryService;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Closes a campaign (ENDED or CANCELLED): freezes its Redis stock, records {@code sold} and
 * releases the unsold reservation. Shared by admin cancel and the end-of-campaign job.
 */
@Component
@RequiredArgsConstructor
class CampaignSettlement {

  private final CampaignMapper campaignMapper;
  private final FlashSaleItemMapper itemMapper;
  private final InventoryService inventoryService;
  private final FlashSaleStockStore stockStore;

  /**
   * The caller's transaction must already hold, in this order, the products rows ({@code FOR
   * SHARE}), the campaign row and the items ({@code FOR UPDATE}); {@code items} come from that
   * locking read, ordered by product id.
   *
   * <p>Redis is deactivated first, so the stock read is final. If the transaction then rolls back,
   * the item simply stays inactive and a retry reads the same frozen stock.
   *
   * @throws BusinessException SERVICE_UNAVAILABLE when a warmed-up item has no stock in Redis (data
   *     loss; needs the FLS-033 reconciliation), so nothing is guessed
   */
  void close(Campaign campaign, List<CampaignItem> items, CampaignStatus finalStatus) {
    List<Integer> sold = new ArrayList<>(items.size());
    for (CampaignItem item : items) {
      sold.add(soldOf(campaign, item));
    }
    // Campaign/item writes first, then inventory (global lock order).
    for (int i = 0; i < items.size(); i++) {
      CampaignItem item = items.get(i);
      if (sold.get(i) != item.getSold()) {
        itemMapper.updateSold(item.getId(), sold.get(i));
      }
    }
    campaignMapper.updateStatus(campaign.getId(), finalStatus);
    for (int i = 0; i < items.size(); i++) {
      CampaignItem item = items.get(i);
      int unsold = item.getTotalStock() - sold.get(i);
      if (unsold > 0) {
        inventoryService.release(item.getProductId(), unsold);
      }
    }
  }

  /**
   * Always deactivates: even without {@code warmedUpAt}, a warm-up whose DB commit failed may have
   * left live keys behind, and those must stop selling too.
   */
  private int soldOf(Campaign campaign, CampaignItem item) {
    OptionalInt remaining = stockStore.deactivate(item.getId());
    if (remaining.isEmpty()) {
      if (campaign.getWarmedUpAt() == null) {
        return item.getSold(); // never loaded, never on sale
      }
      throw new BusinessException(
          ErrorCode.SERVICE_UNAVAILABLE,
          "Flash-sale stock of item " + item.getId() + " is missing in Redis, retry later");
    }
    return Math.max(0, Math.min(item.getTotalStock(), item.getTotalStock() - remaining.getAsInt()));
  }
}
