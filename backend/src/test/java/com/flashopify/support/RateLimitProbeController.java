package com.flashopify.support;

import com.flashopify.common.idempotency.Idempotent;
import com.flashopify.common.ratelimit.RateLimited;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints for the probe policies of application-test.yml (RateLimitIT). */
@RestController
@RequestMapping("/api/v1/_rl-probe")
public class RateLimitProbeController {

  public static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();

  public record Probe(String name) {}

  @RateLimited("test-probe")
  @PostMapping
  public String probe(@RequestBody Probe probe) {
    CALLS.computeIfAbsent(probe.name(), n -> new AtomicInteger()).incrementAndGet();
    return "ok";
  }

  @RateLimited("test-refill")
  @PostMapping("/refill")
  public String refill(@RequestBody Probe probe) {
    return "ok";
  }

  /** Rate limited and idempotent: a 429 must not burn the Idempotency-Key. */
  @Idempotent
  @RateLimited("test-probe")
  @PostMapping("/idempotent")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public String idempotent(@RequestBody Probe probe) {
    CALLS.computeIfAbsent(probe.name(), n -> new AtomicInteger()).incrementAndGet();
    return "ok";
  }
}
