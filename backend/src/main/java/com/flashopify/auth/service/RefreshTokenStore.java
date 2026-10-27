package com.flashopify.auth.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis whitelist of live refresh tokens, key {@code rt:{userId}:{jti}} (ADR-0007). A token is
 * valid only while its key exists; rotation consumes it atomically with a single DEL.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

  private static final int SCAN_COUNT = 100;

  private final StringRedisTemplate redis;

  public void save(long userId, String jti, Duration ttl) {
    redis.opsForValue().set(key(userId, jti), "1", ttl);
  }

  /** Atomically removes the key; true means this caller won the right to rotate the token. */
  public boolean consume(long userId, String jti) {
    return Boolean.TRUE.equals(redis.delete(key(userId, jti)));
  }

  public void revoke(long userId, String jti) {
    redis.delete(key(userId, jti));
  }

  /** Deletes every refresh token of the user using SCAN (never KEYS), in batches. */
  public void revokeAll(long userId) {
    ScanOptions options =
        ScanOptions.scanOptions().match("rt:" + userId + ":*").count(SCAN_COUNT).build();
    List<String> batch = new ArrayList<>();
    try (Cursor<String> cursor = redis.scan(options)) {
      while (cursor.hasNext()) {
        batch.add(cursor.next());
        if (batch.size() >= SCAN_COUNT) {
          redis.delete(batch);
          batch.clear();
        }
      }
    }
    if (!batch.isEmpty()) {
      redis.delete(batch);
    }
  }

  private static String key(long userId, String jti) {
    return "rt:" + userId + ":" + jti;
  }
}
