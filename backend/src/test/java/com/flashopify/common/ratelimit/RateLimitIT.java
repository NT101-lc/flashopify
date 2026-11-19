package com.flashopify.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashopify.common.idempotency.IdempotencyFilter;
import com.flashopify.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FLS-019 against real Redis through the full filter chain. Probe policies (application-test.yml):
 * test-probe = user 3/h + IP 5/h, test-refill = IP 2 with one token back every 300ms. Every test
 * uses its own random IP and user ids, so buckets are never shared.
 */
class RateLimitIT extends AbstractIntegrationTest {

  private static final String PROBE = "/api/v1/_rl-probe";

  @Autowired MockMvc mvc;
  @Autowired StringRedisTemplate redis;

  private static String randomIp() {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
  }

  private static long randomUser() {
    return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
  }

  private static MockHttpServletRequestBuilder probe(String path, String ip, Long user) {
    MockHttpServletRequestBuilder req =
        post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + UUID.randomUUID() + "\"}")
            .with(
                r -> {
                  r.setRemoteAddr(ip);
                  return r;
                });
    return user == null ? req : req.with(jwt().jwt(j -> j.subject(String.valueOf(user))));
  }

  private ResultActions send(String path, String ip, Long user) throws Exception {
    return mvc.perform(probe(path, ip, user));
  }

  @Test
  void overTheUserLimitIs429RateLimitedWithRetryAfter() throws Exception {
    String ip = randomIp();
    long user = randomUser();
    for (int i = 0; i < 3; i++) {
      send(PROBE, ip, user).andExpect(status().isOk());
    }

    send(PROBE, ip, user)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(
            r ->
                assertThat(Long.parseLong(r.getResponse().getHeader("Retry-After")))
                    .isBetween(1L, 3600L));
    // Another user from another IP is not affected.
    send(PROBE, randomIp(), randomUser()).andExpect(status().isOk());
  }

  @Test
  void theIpBucketLimitsManyUsersBehindOneAddress() throws Exception {
    String ip = randomIp();
    for (int i = 0; i < 5; i++) {
      send(PROBE, ip, randomUser()).andExpect(status().isOk());
    }
    send(PROBE, ip, randomUser()).andExpect(status().isTooManyRequests());
  }

  @Test
  void deniedRequestsTakeNoTokenFromTheOtherBucket() throws Exception {
    String ip = randomIp();
    long user = randomUser();
    for (int i = 0; i < 3; i++) {
      send(PROBE, ip, user).andExpect(status().isOk());
    }
    // The user bucket is empty: these are denied and must not drain the IP bucket (2 left).
    for (int i = 0; i < 4; i++) {
      send(PROBE, ip, user).andExpect(status().isTooManyRequests());
    }
    send(PROBE, ip, randomUser()).andExpect(status().isOk());
    send(PROBE, ip, randomUser()).andExpect(status().isOk());
    send(PROBE, ip, randomUser()).andExpect(status().isTooManyRequests());
  }

  @Test
  void tokensComeBackAfterTheRefillPeriod() throws Exception {
    String ip = randomIp();
    send(PROBE + "/refill", ip, randomUser()).andExpect(status().isOk());
    send(PROBE + "/refill", ip, randomUser()).andExpect(status().isOk());
    send(PROBE + "/refill", ip, randomUser())
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "1")); // 300ms rounds up to 1s

    Thread.sleep(400);

    send(PROBE + "/refill", ip, randomUser()).andExpect(status().isOk());
  }

  @Test
  void concurrentRequestsNeverGetMoreThanTheCapacity() throws Exception {
    String ip = randomIp();
    long user = randomUser();
    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return send(PROBE, ip, user).andReturn().getResponse().getStatus();
                }));
      }
      go.countDown();
      AtomicInteger ok = new AtomicInteger();
      for (Future<Integer> f : futures) {
        int s = f.get(30, TimeUnit.SECONDS);
        if (s == 200) {
          ok.incrementAndGet();
        } else {
          assertThat(s).isEqualTo(429);
        }
      }
      assertThat(ok).hasValue(3);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aRateLimitedRequestDoesNotBurnItsIdempotencyKey() throws Exception {
    String ip = randomIp();
    long user = randomUser();
    String body = "{\"name\":\"" + UUID.randomUUID() + "\"}";
    String key = UUID.randomUUID().toString();
    for (int i = 0; i < 3; i++) {
      send(PROBE, ip, user).andExpect(status().isOk()); // drain the user bucket
    }

    mvc.perform(idempotent(ip, user, key, body)).andExpect(status().isTooManyRequests());
    redis.delete(RateLimiter.key("test-probe", "user", String.valueOf(user))); // limit lifted

    mvc.perform(idempotent(ip, user, key, body))
        .andExpect(status().isAccepted())
        .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));
  }

  private static MockHttpServletRequestBuilder idempotent(
      String ip, long user, String key, String body) {
    return post(PROBE + "/idempotent")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .header(IdempotencyFilter.HEADER, key)
        .with(jwt().jwt(j -> j.subject(String.valueOf(user))))
        .with(
            r -> {
              r.setRemoteAddr(ip);
              return r;
            });
  }

  @Test
  void loginIsRateLimitedPerIp() throws Exception {
    String ip = randomIp();
    // Drain this IP's login bucket directly; the test profile keeps login nearly unlimited.
    redis
        .opsForHash()
        .putAll(
            RateLimiter.key("login", "ip", ip),
            java.util.Map.of("tokens", "0", "ts", String.valueOf(Instant.now().toEpochMilli())));

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"whatever-123\"}")
                .with(
                    r -> {
                      r.setRemoteAddr(ip);
                      return r;
                    }))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    // The same login from another IP reaches the controller (wrong credentials → 401).
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"whatever-123\"}")
                .with(
                    r -> {
                      r.setRemoteAddr(randomIp());
                      return r;
                    }))
        .andExpect(status().isUnauthorized());
  }
}
