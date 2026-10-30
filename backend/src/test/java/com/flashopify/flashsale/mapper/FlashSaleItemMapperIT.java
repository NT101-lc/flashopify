package com.flashopify.flashsale.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashopify.flashsale.service.FlashSaleQueryService;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.CatalogFixtures;
import com.flashopify.support.FlashSaleFixtures;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class FlashSaleItemMapperIT extends AbstractIntegrationTest {

  private static final int TIMEOUT_SECONDS = 30;

  @Autowired FlashSaleItemMapper itemMapper;
  @Autowired FlashSaleQueryService queryService;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcTemplate jdbc;

  private CatalogFixtures fx;
  private FlashSaleFixtures flash;
  private long owner;
  private long cat;

  @BeforeEach
  void init() {
    fx = new CatalogFixtures(jdbc);
    flash = new FlashSaleFixtures(jdbc);
    owner = flash.adminUser();
    cat = fx.category("FsMapper " + CatalogFixtures.rnd(), null);
  }

  /** A fresh product placed in one campaign with the given status. */
  private boolean inActiveCampaignWith(String campaignStatus) {
    long pid = fx.product(cat, "ACTIVE");
    long campaign = flash.campaign(owner, campaignStatus);
    flash.item(campaign, pid);
    return itemMapper.existsInActiveCampaign(pid) != null;
  }

  @Test
  void tc17ExistsInActiveCampaignIsTrueForScheduledAndRunning() {
    assertThat(inActiveCampaignWith("SCHEDULED")).isTrue();
    assertThat(inActiveCampaignWith("RUNNING")).isTrue();
  }

  @Test
  void tc17ExistsInActiveCampaignIsFalseForEndedAndCancelled() {
    assertThat(inActiveCampaignWith("ENDED")).isFalse();
    assertThat(inActiveCampaignWith("CANCELLED")).isFalse();
    // A product in no campaign at all is not in an active one either.
    assertThat(itemMapper.existsInActiveCampaign(fx.product(cat, "ACTIVE"))).isNull();
  }

  private record Seen(int snapshotCount, boolean activeInTransaction) {}

  /**
   * The campaign check is a locking read (FOR SHARE), so it sees the latest committed rows even
   * when the surrounding REPEATABLE READ transaction took its snapshot earlier. The writer commits
   * with autocommit and never has to wait for the transaction.
   */
  @Test
  void tc21LockingCampaignCheckSeesCommitAfterTransactionSnapshot() throws Exception {
    long pid = fx.product(cat, "ACTIVE");
    CountDownLatch snapshotTaken = new CountDownLatch(1);
    CountDownLatch committed = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Void> writer =
          pool.submit(
              () -> {
                snapshotTaken.await();
                long campaign = flash.campaign(owner, "SCHEDULED");
                flash.item(campaign, pid); // autocommit: inserted and committed
                committed.countDown();
                return null;
              });

      TransactionTemplate tx = new TransactionTemplate(transactionManager);
      tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      Seen seen =
          tx.execute(
              status -> {
                // A plain read fixes this transaction's snapshot before the commit.
                int snapshotCount =
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flash_sale_items WHERE product_id = ?",
                        Integer.class,
                        pid);
                snapshotTaken.countDown();
                try {
                  committed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
                return new Seen(snapshotCount, queryService.isProductInActiveCampaign(pid));
              });

      writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // any writer failure fails the test
      assertThat(seen.snapshotCount()).as("plain read before the commit saw no item").isZero();
      assertThat(seen.activeInTransaction())
          .as("locking campaign check sees the item committed after the snapshot")
          .isTrue();
    } finally {
      pool.shutdownNow();
    }
  }
}
