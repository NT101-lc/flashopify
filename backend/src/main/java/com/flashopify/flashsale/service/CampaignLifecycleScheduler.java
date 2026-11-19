package com.flashopify.flashsale.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link CampaignLifecycleService}; off when {@code app.flash-sale.scheduler.enabled=false}
 * (tests call the service directly). Safe on several instances: each campaign is claimed with
 * {@code SKIP LOCKED}.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.flash-sale.scheduler",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class CampaignLifecycleScheduler {

  private final CampaignLifecycleService lifecycle;

  @Scheduled(
      fixedDelayString = "${app.flash-sale.scheduler.interval-ms:5000}",
      initialDelayString = "${app.flash-sale.scheduler.interval-ms:5000}")
  void tick() {
    lifecycle.tick();
  }
}
