package com.flashopify.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flashopify.common.idempotency.IdempotencyFilter.CachedBodyRequest;
import com.flashopify.common.idempotency.IdempotencyStore.Claim;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class IdempotencyFilterTest {

  private static final String KEY = "7f3c2a10-5b1e-4c8d";
  private static final long USER = 42L;

  @Mock IdempotentEndpointResolver resolver;
  @Mock IdempotencyStore store;

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private IdempotencyFilter filter;
  private final AtomicInteger chainCalls = new AtomicInteger();

  @BeforeEach
  void init() {
    filter = new IdempotencyFilter(resolver, store, mapper);
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject(String.valueOf(USER))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private static MockHttpServletRequest request(String key, String body) {
    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/flash-sales/7/purchase");
    if (key != null) {
      r.addHeader(IdempotencyFilter.HEADER, key);
    }
    r.setContentType("application/json");
    r.setContent(body.getBytes(StandardCharsets.UTF_8));
    return r;
  }

  /** Chain that echoes the body it reads, proving the cached body still reaches the handler. */
  private FilterChain chain(int status) {
    return (req, res) -> {
      chainCalls.incrementAndGet();
      byte[] in = req.getInputStream().readAllBytes();
      ((jakarta.servlet.http.HttpServletResponse) res).setStatus(status);
      res.setContentType("application/json");
      res.getOutputStream()
          .write(
              ("{\"echo\":" + new String(in, StandardCharsets.UTF_8) + "}")
                  .getBytes(StandardCharsets.UTF_8));
    };
  }

  private JsonNode json(MockHttpServletResponse res) throws IOException {
    return mapper.readTree(res.getContentAsByteArray());
  }

  private MockHttpServletResponse run(MockHttpServletRequest req, FilterChain chain)
      throws ServletException, IOException {
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, chain);
    return res;
  }

  // ---- the four backlog cases ----

  @Test
  void case1MissingHeaderIs400IdempotencyKeyMissingAndNeverRunsTheHandler() throws Exception {
    when(resolver.isIdempotent(org.mockito.ArgumentMatchers.any())).thenReturn(true);

    MockHttpServletResponse res = run(request(null, "{}"), chain(202));

    assertThat(res.getStatus()).isEqualTo(400);
    assertThat(json(res).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_MISSING");
    assertThat(chainCalls).hasValue(0);
    verifyNoInteractions(store);
  }

  @Test
  void case2FinishedKeyReplaysTheStoredResponseWithReplayedHeader() throws Exception {
    when(resolver.isIdempotent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    when(store.claim(eq(USER), eq(KEY), anyString(), anyString()))
        .thenReturn(new Claim(Claim.Kind.DONE, 202, "application/json", "{\"orderNo\":\"FS1\"}"));

    MockHttpServletResponse res = run(request(KEY, "{\"quantity\":1}"), chain(202));

    assertThat(res.getStatus()).isEqualTo(202);
    assertThat(res.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(res.getContentAsString()).isEqualTo("{\"orderNo\":\"FS1\"}");
    assertThat(chainCalls).hasValue(0);
  }

  @Test
  void case3SameKeyOtherRequestIs409IdempotencyKeyConflict() throws Exception {
    when(resolver.isIdempotent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    when(store.claim(eq(USER), eq(KEY), anyString(), anyString()))
        .thenReturn(new Claim(Claim.Kind.CONFLICT, 0, null, null));

    MockHttpServletResponse res = run(request(KEY, "{\"quantity\":2}"), chain(202));

    assertThat(res.getStatus()).isEqualTo(409);
    assertThat(json(res).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    assertThat(chainCalls).hasValue(0);
  }

  @Test
  void case4SameKeyStillRunningIs409RequestInProgress() throws Exception {
    when(resolver.isIdempotent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    when(store.claim(eq(USER), eq(KEY), anyString(), anyString()))
        .thenReturn(new Claim(Claim.Kind.IN_PROGRESS, 0, null, null));

    MockHttpServletResponse res = run(request(KEY, "{}"), chain(202));

    assertThat(res.getStatus()).isEqualTo(409);
    assertThat(json(res).get("code").asText()).isEqualTo("REQUEST_IN_PROGRESS");
    assertThat(chainCalls).hasValue(0);
  }

  // ---- first execution ----

  @Test
  void claimedKeyRunsTheHandlerOnceAndStoresItsResponse() throws Exception {
    when(resolver.isIdempotent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    when(store.claim(eq(USER), eq(KEY), anyString(), anyString()))
        .thenReturn(new Claim(Claim.Kind.CLAIMED, 0, null, null));
    when(store.complete(eq(USER), eq(KEY), anyString(), eq(202), anyString(), anyString()))
        .thenReturn(true);

    MockHttpServletResponse res = run(request(KEY, "{\"quantity\":1}"), chain(202));

    assertThat(res.getStatus()).isEqualTo(202);
    assertThat(res.getContentAsString()).isEqualTo("{\"echo\":{\"quantity\":1}}");
    assertThat(res.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> claimToken = ArgumentCaptor.forClass(String.class);
    verify(store).claim(eq(USER), eq(KEY), anyString(), claimToken.capture());
    verify(store)
        .complete(
            eq(USER), eq(KEY), eq(claimToken.getValue()), eq(202), anyString(), body.capture());
    assertThat(body.getValue()).isEqualTo("{\"echo\":{\"quantity\":1}}");
    assertThat(chainCalls).hasValue(1);
  }

  @Test
  void serverErrorReleasesTheKeyInsteadOfStoringIt() throws Exception {
    when(resolver.isIdempotent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    when(store.claim(eq(USER), eq(KEY), anyString(), anyString()))
        .thenReturn(new Claim(Claim.Kind.CLAIMED, 0, null, null));

    MockHttpServletResponse res = run(request(KEY, "{}"), chain(503));

    assertThat(res.getStatus()).isEqualTo(503);
    verify(store).release(eq(USER), eq(KEY), anyString());
    verify(store, never())
        .complete(
            org.mockito.ArgumentMatchers.anyLong(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            anyString(),
            anyString());
  }

  @Test
  void exceptionReleasesTheKeyAndPropagates() {
    when(resolver.isIdempotent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    when(store.claim(eq(USER), eq(KEY), anyString(), anyString()))
        .thenReturn(new Claim(Claim.Kind.CLAIMED, 0, null, null));
    FilterChain boom =
        (req, res) -> {
          throw new IllegalStateException("boom");
        };

    assertThatThrownBy(() -> run(request(KEY, "{}"), boom))
        .isInstanceOf(IllegalStateException.class);
    verify(store).release(eq(USER), eq(KEY), anyString());
  }

  // ---- guards ----

  @Test
  void malformedKeyIs400ValidationErrorOnTheHeader() throws Exception {
    when(resolver.isIdempotent(org.mockito.ArgumentMatchers.any())).thenReturn(true);

    MockHttpServletResponse res = run(request("short", "{}"), chain(202));

    assertThat(res.getStatus()).isEqualTo(400);
    assertThat(json(res).get("code").asText()).isEqualTo("VALIDATION_ERROR");
    assertThat(json(res).at("/fieldErrors/0/field").asText()).isEqualTo("Idempotency-Key");
    verifyNoInteractions(store);
  }

  @Test
  void endpointsWithoutTheAnnotationAreUntouched() throws Exception {
    when(resolver.isIdempotent(org.mockito.ArgumentMatchers.any())).thenReturn(false);

    MockHttpServletResponse res = run(request(null, "{}"), chain(200));

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chainCalls).hasValue(1);
    verifyNoInteractions(store);
  }

  @Test
  void anonymousRequestsAreLeftToSecurity() throws Exception {
    SecurityContextHolder.clearContext();
    when(resolver.isIdempotent(org.mockito.ArgumentMatchers.any())).thenReturn(true);

    run(request(null, "{}"), chain(401));

    assertThat(chainCalls).hasValue(1);
    verifyNoInteractions(store);
  }

  @Test
  void hashDependsOnMethodPathQueryAndBody() throws Exception {
    String base = IdempotencyFilter.hash(new CachedBodyRequest(request(KEY, "{\"q\":1}")));
    assertThat(IdempotencyFilter.hash(new CachedBodyRequest(request(KEY, "{\"q\":1}"))))
        .isEqualTo(base);
    assertThat(IdempotencyFilter.hash(new CachedBodyRequest(request(KEY, "{\"q\":2}"))))
        .isNotEqualTo(base);
    MockHttpServletRequest otherPath = request(KEY, "{\"q\":1}");
    otherPath.setRequestURI("/api/v1/flash-sales/8/purchase");
    assertThat(IdempotencyFilter.hash(new CachedBodyRequest(otherPath))).isNotEqualTo(base);
    MockHttpServletRequest withQuery = request(KEY, "{\"q\":1}");
    withQuery.setQueryString("x=1");
    assertThat(IdempotencyFilter.hash(new CachedBodyRequest(withQuery))).isNotEqualTo(base);
  }
}
