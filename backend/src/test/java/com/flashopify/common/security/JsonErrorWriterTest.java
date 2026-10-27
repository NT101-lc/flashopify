package com.flashopify.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.auth.domain.Role;
import com.flashopify.auth.domain.User;
import com.flashopify.auth.service.TokenServiceImpl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class JsonErrorWriterTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final JsonErrorWriter writer = new JsonErrorWriter(mapper);
  private final JwtProperties props =
      new JwtProperties(
          "writer-test-secret-key-0123456789-abcdefgh",
          "flashopify",
          Duration.ofMinutes(15),
          Duration.ofDays(7));
  private final JwtConfig config = new JwtConfig();
  private final JwtDecoder decoder = config.accessTokenDecoder(props);

  private String accessToken(Instant issuedAt, JwtProperties signWith) {
    return new TokenServiceImpl(
            config.jwtEncoder(signWith), signWith, Clock.fixed(issuedAt, ZoneOffset.UTC))
        .issue(User.builder().id(1L).role(Role.CUSTOMER).build())
        .accessToken();
  }

  private InvalidBearerTokenException failureFor(String token) {
    try {
      decoder.decode(token);
      throw new AssertionError("token should have been rejected");
    } catch (BadJwtException e) {
      return new InvalidBearerTokenException(e.getMessage(), e);
    }
  }

  private JsonNode commence(org.springframework.security.core.AuthenticationException ex)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    writer.commence(new MockHttpServletRequest(), response, ex);
    assertThat(response.getStatus()).isEqualTo(401);
    return mapper.readTree(response.getContentAsString());
  }

  @Test
  void expiredTokenIsTokenExpired() throws Exception {
    String token = accessToken(Instant.now().minus(Duration.ofHours(1)), props);

    assertThat(commence(failureFor(token)).get("code").asText()).isEqualTo("TOKEN_EXPIRED");
  }

  @Test
  void badSignatureIsInvalidToken() throws Exception {
    JwtProperties other =
        new JwtProperties(
            "other-writer-secret-key-0123456789-abcdefg",
            "flashopify",
            Duration.ofMinutes(15),
            Duration.ofDays(7));
    String token = accessToken(Instant.now(), other);

    JsonNode body = commence(failureFor(token));

    assertThat(body.get("code").asText()).isEqualTo("INVALID_TOKEN");
    assertThat(body.get("timestamp")).isNotNull();
  }

  @Test
  void missingTokenIsUnauthorized() throws Exception {
    assertThat(commence(new InsufficientAuthenticationException("no token")).get("code").asText())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void accessDeniedIsForbidden() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    writer.handle(new MockHttpServletRequest(), response, new AccessDeniedException("no"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(mapper.readTree(response.getContentAsString()).get("code").asText())
        .isEqualTo("FORBIDDEN");
  }
}
