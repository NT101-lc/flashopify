package com.flashopify.support;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.idempotency.Idempotent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only {@link Idempotent} endpoint. Counts real executions per {@code name} so tests can prove
 * a replay never reaches the controller, and can be held open or made to fail on demand.
 */
@RestController
@RequestMapping("/api/v1/_idem-probe")
public class IdempotencyProbeController {

  public static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
  public static final Map<String, CountDownLatch> HOLD = new ConcurrentHashMap<>();

  public record ProbeRequest(String name, String mode) {}

  public record ProbeResponse(String name, int execution) {}

  @Idempotent
  @PostMapping("/{scope}")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ProbeResponse probe(@PathVariable String scope, @RequestBody ProbeRequest request)
      throws InterruptedException {
    int execution =
        CALLS.computeIfAbsent(request.name(), n -> new AtomicInteger()).incrementAndGet();
    CountDownLatch hold = HOLD.get(request.name());
    if (hold != null) {
      hold.await(30, TimeUnit.SECONDS);
    }
    if ("fail".equals(request.mode())) {
      throw new IllegalStateException("probe failure"); // 500
    }
    if ("soldout".equals(request.mode())) {
      throw new BusinessException(ErrorCode.SOLD_OUT, "Sold out"); // 409, a final answer
    }
    return new ProbeResponse(request.name(), execution);
  }

  /** Same path shape without {@link Idempotent}: never needs the header. */
  @PostMapping("/plain/{scope}")
  public ProbeResponse plain(@PathVariable String scope, @RequestBody ProbeRequest request) {
    return new ProbeResponse(request.name(), 0);
  }
}
