package com.flashopify.flashsale.dto;

import com.flashopify.flashsale.domain.Campaign;
import com.flashopify.flashsale.domain.CampaignStatus;
import java.time.Instant;

/** Mirrors {@code AdminCampaignSummary}. */
public record AdminCampaignSummaryResponse(
    long id,
    String name,
    Instant startTime,
    Instant endTime,
    CampaignStatus status,
    int itemCount,
    int totalStock,
    long createdBy,
    Instant createdAt) {

  public static AdminCampaignSummaryResponse from(Campaign c) {
    return new AdminCampaignSummaryResponse(
        c.getId(),
        c.getName(),
        c.getStartTime(),
        c.getEndTime(),
        c.getStatus(),
        c.getItemCount(),
        c.getTotalStock(),
        c.getCreatedBy(),
        c.getCreatedAt());
  }
}
