package com.flashopify.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashopify.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class RefreshTokenStoreIT extends AbstractIntegrationTest {

  @Autowired RefreshTokenStore store;
  @Autowired StringRedisTemplate redis;

  private static long randomUserId() {
    return 1_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
  }

  @Test
  void consumeSucceedsOnceThenFails() {
    long userId = randomUserId();
    store.save(userId, "jti-1", Duration.ofDays(7));

    assertThat(store.consume(userId, "jti-1")).isTrue();
    assertThat(store.consume(userId, "jti-1")).isFalse();
  }

  @Test
  void savedKeyHasRefreshTtl() {
    long userId = randomUserId();
    store.save(userId, "jti-ttl", Duration.ofDays(7));

    Long ttl = redis.getExpire("rt:" + userId + ":jti-ttl");

    assertThat(ttl).isBetween(Duration.ofDays(7).toSeconds() - 60, Duration.ofDays(7).toSeconds());
  }

  @Test
  void revokeRemovesOnlyThatToken() {
    long userId = randomUserId();
    store.save(userId, "a", Duration.ofMinutes(5));
    store.save(userId, "b", Duration.ofMinutes(5));

    store.revoke(userId, "a");

    assertThat(redis.hasKey("rt:" + userId + ":a")).isFalse();
    assertThat(redis.hasKey("rt:" + userId + ":b")).isTrue();
  }

  @Test
  void revokeAllRemovesAllKeysOfUserAndKeepsOthers() {
    long a = randomUserId();
    long b = a + 1;
    for (String jti : new String[] {"1", "2", "3"}) {
      store.save(a, jti, Duration.ofMinutes(5));
    }
    store.save(b, "1", Duration.ofMinutes(5));

    store.revokeAll(a);

    for (String jti : new String[] {"1", "2", "3"}) {
      assertThat(redis.hasKey("rt:" + a + ":" + jti)).isFalse();
    }
    assertThat(redis.hasKey("rt:" + b + ":1")).isTrue();
  }

  @Test
  void revokeAllHandlesMoreKeysThanOneScanBatch() {
    long userId = randomUserId();
    for (int i = 0; i < 250; i++) {
      store.save(userId, "j" + i, Duration.ofMinutes(5));
    }

    store.revokeAll(userId);

    assertThat(redis.keys("rt:" + userId + ":*")).isEmpty();
  }
}
