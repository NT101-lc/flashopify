package com.flashopify.product.service;

import com.flashopify.product.domain.ProductChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Drops cached product details only once the change has committed (ADR-0008). */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCacheEvictionListener {

  private final ProductDetailCache cache;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProductChanged(ProductChangedEvent event) {
    try {
      cache.evict(event.slugs());
    } catch (RuntimeException e) {
      // The DB change is already committed; a failed evict must not turn the request into a 500.
      log.warn("Cache eviction failed for product {}: {}", event.productId(), e.toString());
    }
  }
}
