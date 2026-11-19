package com.flashopify.common.idempotency;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis state of Idempotency-Keys: hash {@code idem:{userId}:{key}} with state PROCESSING or DONE,
 * the request hash, the owner token and, once DONE, the response. Each step is one Lua script, so
 * two concurrent requests can never both claim a key.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

  private static final RedisScript<List> CLAIM = script("claim", List.class);
  private static final RedisScript<Long> COMPLETE = script("complete", Long.class);
  private static final RedisScript<Long> RELEASE = script("release", Long.class);

  private final StringRedisTemplate redis;
  private final IdempotencyProperties properties;

  /** Outcome of {@link #claim}; {@code status/contentType/body} are set only for DONE. */
  public record Claim(Kind kind, int status, String contentType, String body) {

    public enum Kind {
      CLAIMED,
      CONFLICT,
      IN_PROGRESS,
      DONE
    }
  }

  public Claim claim(long userId, String key, String requestHash, String token) {
    List<?> r =
        redis.execute(
            CLAIM,
            List.of(redisKey(userId, key)),
            requestHash,
            token,
            String.valueOf(properties.processingTtl().toMillis()));
    if (r == null || r.isEmpty()) {
      throw new IllegalStateException("Idempotency claim returned no result");
    }
    Claim.Kind kind = Claim.Kind.valueOf(String.valueOf(r.get(0)));
    if (kind != Claim.Kind.DONE) {
      return new Claim(kind, 0, null, null);
    }
    return new Claim(
        kind,
        Integer.parseInt(String.valueOf(r.get(1))),
        String.valueOf(r.get(2)),
        String.valueOf(r.get(3)));
  }

  /** Stores the final response; false when this request no longer owns the key. */
  public boolean complete(
      long userId, String key, String token, int status, String contentType, String body) {
    Long stored =
        redis.execute(
            COMPLETE,
            List.of(redisKey(userId, key)),
            token,
            String.valueOf(status),
            contentType == null ? "" : contentType,
            body,
            String.valueOf(properties.ttl().toMillis()));
    return stored != null && stored == 1;
  }

  /** Drops the claim if still owned, so the same key may be retried. */
  public void release(long userId, String key, String token) {
    redis.execute(RELEASE, List.of(redisKey(userId, key)), token);
  }

  static String redisKey(long userId, String key) {
    return "idem:" + userId + ":" + key;
  }

  private static <T> RedisScript<T> script(String name, Class<T> type) {
    return RedisScript.of(new ClassPathResource("scripts/idempotency/" + name + ".lua"), type);
  }
}
