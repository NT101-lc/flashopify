package com.flashopify.flashsale.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Flash-sale settings. {@code app.flash-sale.warm-up-lead}: how long before {@code startTime} the
 * stock is loaded into Redis; a campaign is no longer editable from that moment.
 */
@ConfigurationProperties("app.flash-sale")
public record FlashSaleProperties(Duration warmUpLead) {

  public FlashSaleProperties {
    if (warmUpLead == null) {
      warmUpLead = Duration.ofMinutes(5);
    }
    if (warmUpLead.isNegative()) {
      throw new IllegalArgumentException("app.flash-sale.warm-up-lead must not be negative");
    }
  }
}
