package com.flashopify.common.ratelimit;

import com.flashopify.common.ratelimit.RateLimitProperties.Bucket;
import com.flashopify.common.ratelimit.RateLimitProperties.Policy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Token buckets in Redis (FLS-019): one Lua script checks and takes a token from every bucket of
 * the policy at once, using the Redis clock. Keys {@code rl:{policy}:user:<id>} and {@code
 * rl:{policy}:ip:<ip>} share the policy hash tag so they live in one cluster slot.
 */
@Component
public class RateLimiter {

  @SuppressWarnings("rawtypes") // RedisScript result type for a multi-bulk reply
  private static final RedisScript<List> TOKEN_BUCKET =
      RedisScript.of(new ClassPathResource("scripts/ratelimit/token_bucket.lua"), List.class);

  private final StringRedisTemplate redis;
  private final RateLimitProperties properties;

  public RateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
    this.redis = redis;
    this.properties = properties;
  }

  /** {@code retryAfter} is zero when allowed. */
  public record Decision(boolean allowed, Duration retryAfter) {

    static final Decision ALLOWED = new Decision(true, Duration.ZERO);
  }

  /**
   * @param userId the authenticated user, or null
   * @param ip the client address, or null
   * @throws IllegalArgumentException for an unknown policy
   */
  public Decision tryAcquire(String policyName, Long userId, String ip) {
    if (!properties.enabled()) {
      return Decision.ALLOWED;
    }
    Policy policy = properties.policies().get(policyName);
    if (policy == null) {
      throw new IllegalArgumentException("Unknown rate-limit policy: " + policyName);
    }
    List<String> keys = new ArrayList<>(2);
    List<String> args = new ArrayList<>(4);
    if (policy.user() != null && userId != null) {
      add(keys, args, key(policyName, "user", String.valueOf(userId)), policy.user());
    }
    if (policy.ip() != null && ip != null && !ip.isBlank()) {
      add(keys, args, key(policyName, "ip", ip), policy.ip());
    }
    if (keys.isEmpty()) {
      return Decision.ALLOWED;
    }
    List<?> r = redis.execute(TOKEN_BUCKET, keys, args.toArray());
    if (r == null || r.size() < 2) {
      throw new IllegalStateException("Rate-limit script returned no result");
    }
    boolean allowed = ((Number) r.get(0)).longValue() == 1;
    return allowed
        ? Decision.ALLOWED
        : new Decision(false, Duration.ofMillis(((Number) r.get(1)).longValue()));
  }

  static String key(String policy, String dimension, String subject) {
    return "rl:{" + policy + "}:" + dimension + ":" + subject;
  }

  private static void add(List<String> keys, List<String> args, String key, Bucket bucket) {
    keys.add(key);
    args.add(String.valueOf(bucket.capacity()));
    args.add(String.valueOf(bucket.refillPeriod().toMillis()));
  }
}
