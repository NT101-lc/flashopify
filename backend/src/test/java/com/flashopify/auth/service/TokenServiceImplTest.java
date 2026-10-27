package com.flashopify.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flashopify.auth.domain.Role;
import com.flashopify.auth.domain.User;
import com.flashopify.common.security.JwtConfig;
import com.flashopify.common.security.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class TokenServiceImplTest {

  private final JwtProperties props =
      new JwtProperties(
          "unit-test-secret-key-0123456789-abcdefghij",
          "flashopify",
          Duration.ofMinutes(15),
          Duration.ofDays(7));
  private final JwtConfig config = new JwtConfig();
  private final JwtDecoder accessDecoder = config.accessTokenDecoder(props);

  private TokenServiceImpl serviceAt(Instant now) {
    return new TokenServiceImpl(config.jwtEncoder(props), props, Clock.fixed(now, ZoneOffset.UTC));
  }

  private static User user() {
    return User.builder().id(42L).role(Role.ADMIN).build();
  }

  @Test
  void accessTokenCarriesExpectedClaims() {
    TokenPair pair = serviceAt(Instant.now()).issue(user());

    Jwt jwt = accessDecoder.decode(pair.accessToken());

    assertThat(jwt.getSubject()).isEqualTo("42");
    assertThat(jwt.getClaimAsString("iss")).isEqualTo("flashopify");
    assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
    assertThat(jwt.getClaimAsString("typ")).isEqualTo("access");
    assertThat(jwt.getId()).isNotBlank();
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds()).isEqualTo(900);
    assertThat(pair.expiresIn()).isEqualTo(900);
  }

  @Test
  void refreshTokenIsRejectedByAccessDecoder() {
    TokenPair pair = serviceAt(Instant.now()).issue(user());

    assertThatThrownBy(() -> accessDecoder.decode(pair.refreshToken()))
        .isInstanceOf(BadJwtException.class);
  }

  @Test
  void refreshTokenHasSevenDayLifetimeAndRefreshType() {
    TokenPair pair = serviceAt(Instant.now()).issue(user());

    Jwt jwt = JwtConfig.decoder(props, JwtConfig.TYPE_REFRESH).decode(pair.refreshToken());

    assertThat(jwt.getClaimAsString("typ")).isEqualTo("refresh");
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
        .isEqualTo(Duration.ofDays(7));
  }

  @Test
  void expiredAccessTokenIsRejected() {
    TokenPair pair = serviceAt(Instant.now().minus(Duration.ofHours(1))).issue(user());

    assertThatThrownBy(() -> accessDecoder.decode(pair.accessToken()))
        .isInstanceOf(BadJwtException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void tokenSignedWithAnotherSecretIsRejected() {
    JwtProperties other =
        new JwtProperties(
            "another-secret-key-0123456789-abcdefghijklm",
            "flashopify",
            Duration.ofMinutes(15),
            Duration.ofDays(7));
    TokenPair pair =
        new TokenServiceImpl(
                config.jwtEncoder(other), other, Clock.fixed(Instant.now(), ZoneOffset.UTC))
            .issue(user());

    assertThatThrownBy(() -> accessDecoder.decode(pair.accessToken()))
        .isInstanceOf(BadJwtException.class);
  }
}
