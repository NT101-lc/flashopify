package com.flashopify.flashsale.domain;

import lombok.Builder;
import lombok.Value;

/** Admin campaign list filter; {@code status} null means all statuses. */
@Value
@Builder
public class CampaignSearchCriteria {

  CampaignStatus status;
  CampaignSort sort;
  long offset;
  int limit;
}
