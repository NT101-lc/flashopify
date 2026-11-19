package com.flashopify.flashsale.service;

import com.flashopify.common.web.PageResponse;
import com.flashopify.flashsale.domain.CampaignStatus;
import com.flashopify.flashsale.dto.AdminCampaignDetailResponse;
import com.flashopify.flashsale.dto.AdminCampaignSummaryResponse;
import com.flashopify.flashsale.dto.CampaignUpsertRequest;

/**
 * Admin management of flash-sale campaigns. Each item reserves its {@code totalStock} in {@code
 * inventory.reserved} for as long as the campaign is active.
 *
 * <p>Lock order for every write: {@code products} rows {@code FOR SHARE} (ascending id), then the
 * campaign row {@code FOR UPDATE}, then its items, then {@code inventory} rows (ascending product
 * id). Any later writer of campaign/item rows that also touches products or inventory (e.g. the
 * FLS-016 end-of-campaign sync) must follow the same order or run in a separate transaction.
 */
public interface AdminCampaignService {

  PageResponse<AdminCampaignSummaryResponse> list(
      CampaignStatus status, String sort, int page, int size);

  AdminCampaignDetailResponse get(long campaignId);

  /**
   * Creates a SCHEDULED campaign and reserves each item's stock.
   *
   * @throws com.flashopify.common.exception.BusinessException VALIDATION_ERROR (time window,
   *     duplicate product), PRODUCT_NOT_FOUND, PRODUCT_NOT_AVAILABLE (not ACTIVE) or
   *     INSUFFICIENT_STOCK
   */
  AdminCampaignDetailResponse create(CampaignUpsertRequest request, long createdBy);

  /**
   * Replaces name, window and items of a SCHEDULED campaign before its warm-up, adjusting the
   * reservations by the difference. Items of products kept in the request keep their id.
   *
   * @throws com.flashopify.common.exception.BusinessException CAMPAIGN_NOT_FOUND,
   *     CAMPAIGN_NOT_EDITABLE plus the errors of {@link #create}
   */
  AdminCampaignDetailResponse update(long campaignId, CampaignUpsertRequest request);

  /**
   * Cancels a SCHEDULED or RUNNING campaign. A warmed-up campaign is deactivated in Redis first;
   * its frozen stock gives {@code sold}, and the unsold stock ({@code totalStock - sold}) is
   * released.
   *
   * @throws com.flashopify.common.exception.BusinessException CAMPAIGN_NOT_FOUND or
   *     CAMPAIGN_NOT_EDITABLE (already ENDED or CANCELLED)
   */
  AdminCampaignSummaryResponse cancel(long campaignId);
}
