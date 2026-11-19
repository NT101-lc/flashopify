package com.flashopify.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.IdempotencyProbeController;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** FLS-018 against real Redis, through the full filter chain (security included). */
class IdempotencyIT extends AbstractIntegrationTest {

  private static final String PROBE = "/api/v1/_idem-probe/a";

  @Autowired MockMvc mvc;
  @Autowired StringRedisTemplate redis;

  private static String newKey() {
    return UUID.randomUUID().toString();
  }

  private static String name() {
    return "n-" + UUID.randomUUID();
  }

  private static String body(String name, String mode) {
    return "{\"name\":\"" + name + "\",\"mode\":\"" + mode + "\"}";
  }

  private ResultActions send(String path, long user, String key, String body) throws Exception {
    var req =
        post(path)
            .with(jwt().jwt(j -> j.subject(String.valueOf(user))))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (key != null) {
      req.header(IdempotencyFilter.HEADER, key);
    }
    return mvc.perform(req);
  }

  private static int calls(String name) {
    AtomicInteger c = IdempotencyProbeController.CALLS.get(name);
    return c == null ? 0 : c.get();
  }

  @Test
  void tc03SameKeyThreeTimesRunsOnceAndReplaysTheSameBody() throws Exception {
    String key = newKey();
    String n = name();

    String first =
        send(PROBE, 1, key, body(n, "ok"))
            .andExpect(status().isAccepted())
            .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (int i = 0; i < 2; i++) {
      send(PROBE, 1, key, body(n, "ok"))
          .andExpect(status().isAccepted())
          .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
          .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(first));
    }
    assertThat(calls(n)).isEqualTo(1);
    Long ttl = redis.getExpire(IdempotencyStore.redisKey(1, key), TimeUnit.SECONDS);
    assertThat(ttl).isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
  }

  @Test
  void missingHeaderIs400OnlyOnAnnotatedEndpoints() throws Exception {
    String n = name();
    send(PROBE, 1, null, body(n, "ok"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));
    send("/api/v1/_idem-probe/plain/a", 1, null, body(n, "ok")).andExpect(status().isOk());
    assertThat(calls(n)).isZero();
  }

  @Test
  void sameKeyWithAnotherBodyOrPathIs409Conflict() throws Exception {
    String key = newKey();
    String n = name();
    send(PROBE, 1, key, body(n, "ok")).andExpect(status().isAccepted());

    send(PROBE, 1, key, body(name(), "ok"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    send("/api/v1/_idem-probe/b", 1, key, body(n, "ok"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    assertThat(calls(n)).isEqualTo(1);
  }

  @Test
  void sameKeyWhileTheFirstRequestRunsIs409InProgress() throws Exception {
    String key = newKey();
    String n = name();
    CountDownLatch hold = new CountDownLatch(1);
    IdempotencyProbeController.HOLD.put(n, hold);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<MockHttpServletResponse> first =
          pool.submit(() -> send(PROBE, 1, key, body(n, "ok")).andReturn().getResponse());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (calls(n) == 0 && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertThat(calls(n)).isEqualTo(1);

      send(PROBE, 1, key, body(n, "ok"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));

      hold.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(202);
      send(PROBE, 1, key, body(n, "ok"))
          .andExpect(status().isAccepted())
          .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));
    } finally {
      hold.countDown();
      IdempotencyProbeController.HOLD.remove(n);
      pool.shutdownNow();
    }
  }

  @Test
  void keysAreScopedPerUser() throws Exception {
    String key = newKey();
    String n = name();
    send(PROBE, 1, key, body(n, "ok")).andExpect(status().isAccepted());
    send(PROBE, 2, key, body(n, "ok"))
        .andExpect(status().isAccepted())
        .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));
    assertThat(calls(n)).isEqualTo(2);
  }

  @Test
  void serverErrorReleasesTheKeySoTheRetryRunsAgain() throws Exception {
    String key = newKey();
    String n = name();
    send(PROBE, 1, key, body(n, "fail")).andExpect(status().isInternalServerError());
    assertThat(redis.hasKey(IdempotencyStore.redisKey(1, key))).isFalse();

    send(PROBE, 1, key, body(n, "fail")).andExpect(status().isInternalServerError());
    assertThat(calls(n)).isEqualTo(2);
  }

  @Test
  void businessConflictIsAFinalAnswerAndIsReplayed() throws Exception {
    String key = newKey();
    String n = name();
    send(PROBE, 1, key, body(n, "soldout"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOLD_OUT"));
    send(PROBE, 1, key, body(n, "soldout"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOLD_OUT"))
        .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));
    assertThat(calls(n)).isEqualTo(1);
  }

  @Test
  void twentyConcurrentRequestsWithOneKeyExecuteExactlyOnce() throws Exception {
    String key = newKey();
    String n = name();
    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch ready = new CountDownLatch(threads);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<MockHttpServletResponse> task =
            () -> {
              ready.countDown();
              go.await();
              return send(PROBE, 1, key, body(n, "ok")).andReturn().getResponse();
            };
        futures.add(pool.submit(task));
      }
      ready.await();
      go.countDown();
      int accepted = 0;
      for (Future<MockHttpServletResponse> f : futures) {
        MockHttpServletResponse r = f.get(30, TimeUnit.SECONDS);
        if (r.getStatus() == 202) {
          accepted++;
        } else {
          assertThat(r.getStatus()).isEqualTo(409);
          assertThat(r.getContentAsString()).contains("REQUEST_IN_PROGRESS");
        }
      }
      assertThat(accepted).isPositive();
      assertThat(calls(n)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
