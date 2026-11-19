package com.flashopify.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.exception.ErrorResponse;
import com.flashopify.common.exception.ErrorResponse.FieldError;
import com.flashopify.common.idempotency.IdempotencyStore.Claim;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Enforces {@link Idempotent} (FLS-018). Registered by {@link IdempotencyConfig} after the Spring
 * Security chain (order 0 &gt; -100), so the caller is known; keys are scoped per user.
 *
 * <ul>
 *   <li>missing header → 400 {@code IDEMPOTENCY_KEY_MISSING}; malformed → 400 {@code
 *       VALIDATION_ERROR}
 *   <li>same key, other request (method, path, query or body) → 409 {@code
 *       IDEMPOTENCY_KEY_CONFLICT}
 *   <li>same key while the first request runs → 409 {@code REQUEST_IN_PROGRESS}
 *   <li>same key after it finished → the stored response with {@code Idempotent-Replayed: true}
 * </ul>
 *
 * <p>Responses below 500 are stored for the TTL (a business 4xx such as SOLD_OUT is a final answer
 * too). A 5xx or an exception releases the key so the client can retry it.
 */
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

  public static final String HEADER = "Idempotency-Key";
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  /** openapi.yaml: 8–64 characters; a conservative charset keeps the Redis key readable. */
  private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");

  private final IdempotentEndpointResolver resolver;
  private final IdempotencyStore store;
  private final ObjectMapper objectMapper;

  public IdempotencyFilter(
      IdempotentEndpointResolver resolver, IdempotencyStore store, ObjectMapper objectMapper) {
    this.resolver = resolver;
    this.store = store;
    this.objectMapper = objectMapper;
  }

  /** Reads never create orders; skip the handler lookup for them. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String method = request.getMethod();
    return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!resolver.isIdempotent(request)) {
      chain.doFilter(request, response);
      return;
    }
    Long userId = currentUserId();
    if (userId == null) {
      // Idempotent endpoints are authenticated; let the dispatcher/security produce the 401.
      chain.doFilter(request, response);
      return;
    }
    String key = request.getHeader(HEADER);
    if (key == null || key.isBlank()) {
      writeError(
          response,
          HttpStatus.BAD_REQUEST,
          ErrorCode.IDEMPOTENCY_KEY_MISSING,
          "Header Idempotency-Key is required",
          List.of());
      return;
    }
    if (!KEY_FORMAT.matcher(key).matches()) {
      writeError(
          response,
          HttpStatus.BAD_REQUEST,
          ErrorCode.VALIDATION_ERROR,
          "Request validation failed",
          List.of(new FieldError(HEADER, "must be 8-64 characters of [A-Za-z0-9._:-]", null)));
      return;
    }

    CachedBodyRequest cached = new CachedBodyRequest(request);
    String token = UUID.randomUUID().toString();
    Claim claim = store.claim(userId, key, hash(cached), token);
    switch (claim.kind()) {
      case CONFLICT ->
          writeError(
              response,
              HttpStatus.CONFLICT,
              ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
              "Idempotency-Key was already used for a different request",
              List.of());
      case IN_PROGRESS ->
          writeError(
              response,
              HttpStatus.CONFLICT,
              ErrorCode.REQUEST_IN_PROGRESS,
              "A request with this Idempotency-Key is still being processed",
              List.of());
      case DONE -> replay(response, claim);
      case CLAIMED -> proceed(cached, response, chain, userId, key, token);
      default -> throw new IllegalStateException("Unknown claim " + claim.kind());
    }
  }

  private void proceed(
      CachedBodyRequest request,
      HttpServletResponse response,
      FilterChain chain,
      long userId,
      String key,
      String token)
      throws ServletException, IOException {
    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    try {
      chain.doFilter(request, wrapper);
    } catch (ServletException | IOException | RuntimeException e) {
      store.release(userId, key, token);
      throw e;
    }
    int status = wrapper.getStatus();
    try {
      if (status >= 500) {
        store.release(userId, key, token);
      } else {
        String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (!store.complete(userId, key, token, status, wrapper.getContentType(), body)) {
          log.warn("Idempotency claim of user {} expired before the response was stored", userId);
        }
      }
    } catch (RuntimeException e) {
      // The response is already decided; failing it now would invite a duplicate retry.
      log.error("Could not store the idempotent response of user {}", userId, e);
    }
    wrapper.copyBodyToResponse();
  }

  private static void replay(HttpServletResponse response, Claim claim) throws IOException {
    response.setStatus(claim.status());
    if (claim.contentType() != null && !claim.contentType().isEmpty()) {
      response.setContentType(claim.contentType());
    }
    response.setHeader(REPLAYED_HEADER, "true");
    byte[] body = claim.body().getBytes(StandardCharsets.UTF_8);
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
  }

  private void writeError(
      HttpServletResponse response,
      HttpStatus status,
      ErrorCode code,
      String message,
      List<FieldError> fieldErrors)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getOutputStream(), ErrorResponse.of(code, message, fieldErrors));
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

  /** SHA-256 over method, path, query and body: any difference is a different request. */
  static String hash(CachedBodyRequest request) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      String head =
          request.getMethod()
              + ' '
              + request.getRequestURI()
              + '?'
              + (request.getQueryString() == null ? "" : request.getQueryString())
              + '\n';
      sha.update(head.getBytes(StandardCharsets.UTF_8));
      sha.update(request.body());
      return HexFormat.of().formatHex(sha.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** Reads the body once so it can be hashed and still be read by the controller. */
  static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request) throws IOException {
      super(request);
      this.body = StreamUtils.copyToByteArray(request.getInputStream());
    }

    byte[] body() {
      return body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("async reads are not supported");
        }

        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return in.read(b, off, len);
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      return new BufferedReader(
          new InputStreamReader(
              getInputStream(),
              encoding == null
                  ? StandardCharsets.UTF_8
                  : java.nio.charset.Charset.forName(encoding)));
    }
  }
}
