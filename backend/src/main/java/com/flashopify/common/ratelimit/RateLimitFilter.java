package com.flashopify.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.exception.ErrorResponse;
import com.flashopify.common.ratelimit.RateLimiter.Decision;
import com.flashopify.common.web.HandlerMethodLookup;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces {@link RateLimited}. Registered by {@link RateLimitConfig} after Spring Security (so the
 * user is known) and before the idempotency filter (so a 429 never claims an Idempotency-Key).
 *
 * <p>The client IP is {@code request.getRemoteAddr()}. Behind a load balancer the deployment must
 * set {@code server.forward-headers-strategy} so that it is the real client address; trusting
 * {@code X-Forwarded-For} directly would let any client choose its own bucket.
 *
 * <p>If Redis is unreachable the request is let through (fail open) and the failure is logged: the
 * login lockout and the flash-sale per-user limit still protect the critical paths.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

  private final HandlerMethodLookup lookup;
  private final RateLimiter limiter;
  private final ObjectMapper objectMapper;

  public RateLimitFilter(
      HandlerMethodLookup lookup, RateLimiter limiter, ObjectMapper objectMapper) {
    this.lookup = lookup;
    this.limiter = limiter;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<RateLimited> annotation =
        lookup.find(request).map(m -> m.getMethodAnnotation(RateLimited.class));
    if (annotation.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }
    Decision decision;
    try {
      decision =
          limiter.tryAcquire(annotation.get().value(), currentUserId(), request.getRemoteAddr());
    } catch (RuntimeException e) {
      if (e instanceof IllegalArgumentException) {
        throw e; // unknown policy: a programming error, never silently unlimited
      }
      log.warn("Rate limiter unavailable, letting the request through: {}", e.toString());
      chain.doFilter(request, response);
      return;
    }
    if (decision.allowed()) {
      chain.doFilter(request, response);
      return;
    }
    long seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getOutputStream(),
        ErrorResponse.of(ErrorCode.RATE_LIMITED, "Too many requests, retry in " + seconds + "s"));
  }

  private static Long currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      try {
        return Long.parseLong(jwt.getSubject());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}
