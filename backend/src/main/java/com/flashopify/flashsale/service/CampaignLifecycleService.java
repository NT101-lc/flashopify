package com.flashopify.flashsale.service;

import com.flashopify.flashsale.domain.Campaign;
import com.flashopify.flashsale.domain.CampaignItem;
import com.flashopify.flashsale.domain.CampaignStatus;
import com.flashopify.flashsale.mapper.CampaignMapper;
import com.flashopify.flashsale.mapper.FlashSaleItemMapper;
import com.flashopify.product.service.ProductQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Campaign lifecycle (FLS-016): warm-up into Redis {@code warmUpLead} before start, SCHEDULED →
 * RUNNING at start, → ENDED at end with {@code sold} synced back and the unsold stock released.
 *
 * <p>Each campaign is handled in its own transaction that re-checks the conditions under {@code FOR
 * UPDATE SKIP LOCKED}, so several instances can run the job at once without doing the same work
 * twice. Lock failures (deadlock, lock wait timeout) are retried a few times; any other failure is
 * logged and the campaign is retried on the next tick.
 */
@Slf4j
@Service
public class CampaignLifecycleService {

  static final int MAX_ATTEMPTS = 3;

  private final CampaignMapper campaignMapper;
  private final FlashSaleItemMapper itemMapper;
  private final ProductQueryService productQueryService;
  private final FlashSaleStockStore stockStore;
  private final CampaignSettlement settlement;
  private final FlashSaleProperties properties;
  private final Clock clock;
  private final TransactionTemplate tx;

  public CampaignLifecycleService(
      CampaignMapper campaignMapper,
      FlashSaleItemMapper itemMapper,
      ProductQueryService productQueryService,
      FlashSaleStockStore stockStore,
      CampaignSettlement settlement,
      FlashSaleProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.campaignMapper = campaignMapper;
    this.itemMapper = itemMapper;
    this.productQueryService = productQueryService;
    this.stockStore = stockStore;
    this.settlement = settlement;
    this.properties = properties;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** One scheduler tick: warm-up, then start, then end. Returns the campaigns changed. */
  public int tick() {
    return warmUpDue() + startDue() + endDue();
  }

  public int warmUpDue() {
    Instant now = clock.instant();
    List<Long> ids =
        campaignMapper.findIdsDueForWarmUp(
            now.plus(properties.warmUpLead()), now, properties.schedulerBatchSize());
    return runEach("warm-up", ids, this::warmUp);
  }

  public int startDue() {
    List<Long> ids =
        campaignMapper.findIdsDueForStart(clock.instant(), properties.schedulerBatchSize());
    return runEach("start", ids, this::start);
  }

  public int endDue() {
    List<Long> ids =
        campaignMapper.findIdsDueForEnd(clock.instant(), properties.schedulerBatchSize());
    return runEach("end", ids, this::end);
  }

  /** Must run inside a transaction. */
  boolean warmUp(long campaignId) {
    Campaign c = campaignMapper.lockByIdSkipLocked(campaignId);
    Instant now = clock.instant();
    if (c == null
        || c.getStatus() != CampaignStatus.SCHEDULED
        || c.getWarmedUpAt() != null
        || c.getStartTime().minus(properties.warmUpLead()).isAfter(now)
        || !c.getEndTime().isAfter(now)) {
      return false;
    }
    Instant expireAt = c.getEndTime().plus(properties.keyRetention());
    for (CampaignItem item : itemMapper.findByCampaignId(campaignId)) {
      stockStore.warmUp(c, item, expireAt);
    }
    campaignMapper.markWarmedUp(campaignId, now);
    log.info("Flash-sale campaign {} warmed up", campaignId);
    return true;
  }

  /** Must run inside a transaction. */
  boolean start(long campaignId) {
    Campaign c = campaignMapper.lockByIdSkipLocked(campaignId);
    Instant now = clock.instant();
    if (c == null
        || c.getStatus() != CampaignStatus.SCHEDULED
        || c.getWarmedUpAt() == null
        || c.getStartTime().isAfter(now)
        || !c.getEndTime().isAfter(now)) {
      return false;
    }
    campaignMapper.updateStatus(campaignId, CampaignStatus.RUNNING);
    log.info("Flash-sale campaign {} is running", campaignId);
    return true;
  }

  /** Must run inside a transaction. Lock order: products, campaign, items, inventory. */
  boolean end(long campaignId) {
    Set<Long> productIds = new HashSet<>();
    itemMapper.findByCampaignId(campaignId).forEach(i -> productIds.add(i.getProductId()));
    productQueryService.lockForShare(productIds);

    Campaign c = campaignMapper.lockByIdSkipLocked(campaignId);
    if (c == null
        || (c.getStatus() != CampaignStatus.SCHEDULED && c.getStatus() != CampaignStatus.RUNNING)
        || c.getEndTime().isAfter(clock.instant())) {
      return false;
    }
    List<CampaignItem> items = itemMapper.lockByCampaignId(campaignId);
    if (items.stream().anyMatch(i -> !productIds.contains(i.getProductId()))) {
      return false; // items changed since the plain read; the next tick sees the new set
    }
    settlement.close(c, items, CampaignStatus.ENDED);
    log.info("Flash-sale campaign {} ended", campaignId);
    return true;
  }

  private int runEach(String step, List<Long> ids, LongPredicate work) {
    int done = 0;
    for (long id : ids) {
      try {
        if (inTransactionWithRetry(id, work)) {
          done++;
        }
      } catch (RuntimeException e) {
        log.error("Flash-sale {} failed for campaign {}", step, id, e);
      }
    }
    return done;
  }

  private boolean inTransactionWithRetry(long id, LongPredicate work) {
    for (int attempt = 1; ; attempt++) {
      try {
        return Boolean.TRUE.equals(tx.execute(status -> work.test(id)));
      } catch (PessimisticLockingFailureException e) {
        if (attempt >= MAX_ATTEMPTS) {
          throw e;
        }
        log.warn("Lock failure on campaign {} (attempt {}), retrying", id, attempt);
      }
    }
  }
}
