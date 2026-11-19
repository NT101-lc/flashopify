package com.flashopify.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flashopify.common.ratelimit.RateLimitProperties.Bucket;
import com.flashopify.common.ratelimit.RateLimitProperties.Policy;
import com.flashopify.common.ratelimit.RateLimiter.Decision;
import com.flashopify.common.web.HandlerMethodLookup;
import jakarta.servlet.FilterChain;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

  @Mock HandlerMethodLookup lookup;
  @Mock RateLimiter limiter;

  private RateLimitFilter filter;
  private final AtomicInteger chainCalls = new AtomicInteger();
  private final FilterChain chain = (req, res) -> chainCalls.incrementAndGet();

  /** Handler methods with and without the annotation. */
  static class Handlers {
    @RateLimited("login")
    public void limited() {}

    public void plain() {}

    @RateLimited("missing")
    public void misconfigured() {}
  }

  private static HandlerMethod handler(String name) throws NoSuchMethodException {
    Method m = Handlers.class.getMethod(name);
    return new HandlerMethod(new Handlers(), m);
  }

  @BeforeEach
  void init() {
    filter =
        new RateLimitFilter(
            lookup, limiter, new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  private MockHttpServletResponse run() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    req.setRemoteAddr("10.1.2.3");
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, chain);
    return res;
  }

  @Test
  void endpointsWithoutTheAnnotationAreNotLimited() throws Exception {
    when(lookup.find(any())).thenReturn(Optional.of(handler("plain")));
    run();
    assertThat(chainCalls).hasValue(1);
    verifyNoInteractions(limiter);
  }

  @Test
  void allowedRequestsReachTheHandlerWithIpAndNoUserWhenAnonymous() throws Exception {
    when(lookup.find(any())).thenReturn(Optional.of(handler("limited")));
    when(limiter.tryAcquire(eq("login"), isNull(), eq("10.1.2.3"))).thenReturn(Decision.ALLOWED);
    run();
    assertThat(chainCalls).hasValue(1);
  }

  @Test
  void deniedRequestsAre429WithRetryAfterRoundedUpToSeconds() throws Exception {
    when(lookup.find(any())).thenReturn(Optional.of(handler("limited")));
    when(limiter.tryAcquire(anyString(), any(), anyString()))
        .thenReturn(new Decision(false, Duration.ofMillis(2_001)));

    MockHttpServletResponse res = run();

    assertThat(res.getStatus()).isEqualTo(429);
    assertThat(res.getHeader("Retry-After")).isEqualTo("3");
    assertThat(res.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
    assertThat(chainCalls).hasValue(0);
  }

  @Test
  void retryAfterIsAtLeastOneSecond() throws Exception {
    when(lookup.find(any())).thenReturn(Optional.of(handler("limited")));
    when(limiter.tryAcquire(anyString(), any(), anyString()))
        .thenReturn(new Decision(false, Duration.ofMillis(5)));
    assertThat(run().getHeader("Retry-After")).isEqualTo("1");
  }

  @Test
  void redisOutageFailsOpen() throws Exception {
    when(lookup.find(any())).thenReturn(Optional.of(handler("limited")));
    when(limiter.tryAcquire(anyString(), any(), anyString()))
        .thenThrow(new RedisConnectionFailureException("down"));
    run();
    assertThat(chainCalls).hasValue(1);
  }

  @Test
  void unknownPolicyIsAProgrammingErrorNotAnUnlimitedEndpoint() throws Exception {
    when(lookup.find(any())).thenReturn(Optional.of(handler("misconfigured")));
    when(limiter.tryAcquire(anyString(), any(), anyString()))
        .thenThrow(new IllegalArgumentException("Unknown rate-limit policy: missing"));
    assertThatThrownBy(this::run).isInstanceOf(IllegalArgumentException.class);
    assertThat(chainCalls).hasValue(0);
  }

  @Test
  void startupCheckRejectsAnAnnotationWithoutAPolicy() throws Exception {
    RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
    when(mapping.getHandlerMethods())
        .thenReturn(
            Map.of(
                RequestMappingInfo.paths("/a").build(), handler("limited"),
                RequestMappingInfo.paths("/b").build(), handler("misconfigured")));
    RateLimitProperties props =
        new RateLimitProperties(
            true, Map.of("login", new Policy(null, new Bucket(1, Duration.ofSeconds(1)))));

    assertThatThrownBy(() -> RateLimitConfig.checkPolicies(mapping, props))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing");
  }

  @Test
  void bucketsRejectNonPositiveSettings() {
    assertThatThrownBy(() -> new Bucket(0, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Bucket(1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
