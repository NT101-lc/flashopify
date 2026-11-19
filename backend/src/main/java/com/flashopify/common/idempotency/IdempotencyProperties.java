package com.flashopify.common.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Idempotency-Key settings (prefix {@code app.idempotency}).
 *
 * @param ttl how long a finished response is kept for replay
 * @param processingTtl how long an unfinished claim blocks the key; bounds the lock-out after a
 *     crash mid-request, so it must exceed the slowest idempotent request
 */
@ConfigurationProperties("app.idempotency")
public record IdempotencyProperties(Duration ttl, Duration processingTtl) {

  @ConstructorBinding
  public IdempotencyProperties {
    if (ttl == null) {
      ttl = Duration.ofHours(24);
    }
    if (processingTtl == null) {
      processingTtl = Duration.ofSeconds(60);
    }
    if (ttl.isNegative() || ttl.isZero() || processingTtl.isNegative() || processingTtl.isZero()) {
      throw new IllegalArgumentException("app.idempotency TTLs must be positive");
    }
  }

  /** Defaults. */
  public IdempotencyProperties() {
    this(null, null);
  }
}
