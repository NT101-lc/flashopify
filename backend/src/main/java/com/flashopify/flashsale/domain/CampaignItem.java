package com.flashopify.flashsale.domain;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Row of {@code flash_sale_items}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignItem {

  private Long id;
  private Long campaignId;
  private Long productId;
  private BigDecimal salePrice;
  private int totalStock;
  private int sold;
  private int limitPerUser;
}
