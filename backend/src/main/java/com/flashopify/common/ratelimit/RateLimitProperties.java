package com.flashopify.common.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Rate-limit policies (prefix {@code app.rate-limit}). Each policy has an optional bucket per
 * authenticated user and per client IP; a request needs a token from every bucket that applies.
 *
 * <pre>
 * app.rate-limit.policies.login.ip.capacity: 10
 * app.rate-limit.policies.login.ip.refill-period: 6s   # one token back every 6s
 * </pre>
 *
 * @param enabled false turns every check off (e.g. for a local load test)
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(Boolean enabled, Map<String, Policy> policies) {

  @ConstructorBinding
  public RateLimitProperties {
    if (enabled == null) {
      enabled = true;
    }
    policies = policies == null ? Map.of() : Map.copyOf(policies);
  }

  /** Both buckets are optional; the user bucket only applies to authenticated requests. */
  public record Policy(Bucket user, Bucket ip) {}

  /**
   * @param capacity burst size: tokens available after a quiet period
   * @param refillPeriod time to earn back one token
   */
  public record Bucket(int capacity, Duration refillPeriod) {

    public Bucket {
      if (capacity < 1) {
        throw new IllegalArgumentException("rate-limit capacity must be at least 1");
      }
      if (refillPeriod == null || refillPeriod.toMillis() < 1) {
        throw new IllegalArgumentException("rate-limit refill-period must be at least 1ms");
      }
    }
  }
}
