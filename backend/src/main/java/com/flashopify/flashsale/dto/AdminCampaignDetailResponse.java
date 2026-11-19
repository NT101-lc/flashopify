package com.flashopify.flashsale.dto;

import com.flashopify.flashsale.domain.Campaign;
import com.flashopify.flashsale.domain.CampaignStatus;
import java.time.Instant;
import java.util.List;

/** Mirrors {@code AdminCampaignDetail}. */
public record AdminCampaignDetailResponse(
    long id,
    String name,
    Instant startTime,
    Instant endTime,
    CampaignStatus status,
    int itemCount,
    int totalStock,
    long createdBy,
    Instant createdAt,
    List<AdminCampaignItemResponse> items) {

  public static AdminCampaignDetailResponse of(Campaign c, List<AdminCampaignItemResponse> items) {
    return new AdminCampaignDetailResponse(
        c.getId(),
        c.getName(),
        c.getStartTime(),
        c.getEndTime(),
        c.getStatus(),
        c.getItemCount(),
        c.getTotalStock(),
        c.getCreatedBy(),
        c.getCreatedAt(),
        items);
  }
}
