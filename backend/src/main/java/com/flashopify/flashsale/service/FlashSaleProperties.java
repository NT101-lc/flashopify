package com.flashopify.flashsale.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Flash-sale settings (prefix {@code app.flash-sale}).
 *
 * @param warmUpLead how long before {@code startTime} the stock is loaded into Redis; a campaign is
 *     no longer editable from that moment
 * @param keyRetention how long the Redis keys outlive {@code endTime}
 * @param schedulerBatchSize campaigns handled per lifecycle step and tick
 */
@ConfigurationProperties("app.flash-sale")
public record FlashSaleProperties(
    Duration warmUpLead, Duration keyRetention, Integer schedulerBatchSize) {

  @ConstructorBinding
  public FlashSaleProperties {
    if (warmUpLead == null) {
      warmUpLead = Duration.ofMinutes(5);
    }
    if (warmUpLead.isNegative()) {
      throw new IllegalArgumentException("app.flash-sale.warm-up-lead must not be negative");
    }
    if (keyRetention == null) {
      keyRetention = Duration.ofDays(1);
    }
    if (schedulerBatchSize == null) {
      schedulerBatchSize = 50;
    }
  }

  /** Test/convenience constructor with default retention and batch size. */
  public FlashSaleProperties(Duration warmUpLead) {
    this(warmUpLead, null, null);
  }
}
