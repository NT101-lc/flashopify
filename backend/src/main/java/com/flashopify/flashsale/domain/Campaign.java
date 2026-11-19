package com.flashopify.flashsale.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Row of {@code flash_sale_campaigns}. {@code itemCount}/{@code totalStock} are aggregates filled
 * only by the summary queries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Campaign {

  private Long id;
  private String name;
  private Instant startTime;
  private Instant endTime;
  private CampaignStatus status;
  private Long createdBy;
  private Instant createdAt;
  private int itemCount;
  private int totalStock;
}
