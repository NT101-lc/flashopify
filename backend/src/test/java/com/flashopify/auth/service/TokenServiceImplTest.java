package com.flashopify.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashopify.auth.domain.Role;
import com.flashopify.auth.domain.User;
import com.flashopify.auth.domain.UserStatus;
import com.flashopify.auth.mapper.UserMapper;
import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.security.JwtConfig;
import com.flashopify.common.security.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
  private final JwtDecoder refreshDecoder = config.refreshTokenDecoder(props);
  private final RefreshTokenStore store = mock(RefreshTokenStore.class);
  private final UserMapper userMapper = mock(UserMapper.class);

  private TokenServiceImpl serviceAt(Instant now) {
    return new TokenServiceImpl(
        config.jwtEncoder(props),
        refreshDecoder,
        props,
        Clock.fixed(now, ZoneOffset.UTC),
        store,
        userMapper);
  }

  private static User user() {
    return User.builder().id(42L).role(Role.ADMIN).status(UserStatus.ACTIVE).build();
  }

  private static void assertInvalidToken(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_TOKEN));
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

    Jwt jwt = refreshDecoder.decode(pair.refreshToken());

    assertThat(jwt.getClaimAsString("typ")).isEqualTo("refresh");
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
        .isEqualTo(Duration.ofDays(7));
  }

  @Test
  void issueWhitelistsRefreshJtiInRedis() {
    TokenPair pair = serviceAt(Instant.now()).issue(user());

    Jwt refresh = refreshDecoder.decode(pair.refreshToken());
    verify(store).save(42L, refresh.getId(), Duration.ofDays(7));
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
                config.jwtEncoder(other),
                refreshDecoder,
                other,
                Clock.fixed(Instant.now(), ZoneOffset.UTC),
                store,
                userMapper)
            .issue(user());

    assertThatThrownBy(() -> accessDecoder.decode(pair.accessToken()))
        .isInstanceOf(BadJwtException.class);
  }

  @Test
  void refreshRotatesWhenJtiIsConsumed() {
    TokenServiceImpl service = serviceAt(Instant.now());
    TokenPair first = service.issue(user());
    String jti = refreshDecoder.decode(first.refreshToken()).getId();
    when(store.consume(42L, jti)).thenReturn(true);
    when(userMapper.findById(42L)).thenReturn(user());

    RefreshedSession session = service.refresh(first.refreshToken());

    assertThat(session.user().getId()).isEqualTo(42L);
    String newJti = refreshDecoder.decode(session.tokens().refreshToken()).getId();
    assertThat(newJti).isNotEqualTo(jti);
    verify(store).save(eq(42L), eq(newJti), any());
    verify(store, never()).revokeAll(anyLong());
  }

  @Test
  void refreshReuseRevokesAllSessionsAndIs401() {
    TokenServiceImpl service = serviceAt(Instant.now());
    TokenPair first = service.issue(user());
    when(store.consume(eq(42L), anyString())).thenReturn(false);

    assertInvalidToken(() -> service.refresh(first.refreshToken()));

    verify(store).revokeAll(42L);
    verify(userMapper, never()).findById(anyLong());
  }

  @Test
  void refreshGarbageTokenIs401() {
    assertInvalidToken(() -> serviceAt(Instant.now()).refresh("not-a-jwt"));
    verify(store, never()).consume(anyLong(), anyString());
  }

  @Test
  void refreshExpiredTokenIs401() {
    TokenServiceImpl old = serviceAt(Instant.now().minus(Duration.ofDays(8)));
    TokenPair pair = old.issue(user());

    assertInvalidToken(() -> serviceAt(Instant.now()).refresh(pair.refreshToken()));
    verify(store, never()).consume(anyLong(), anyString());
  }

  @Test
  void refreshWithAccessTokenIs401() {
    TokenServiceImpl service = serviceAt(Instant.now());
    TokenPair pair = service.issue(user());

    assertInvalidToken(() -> service.refresh(pair.accessToken()));
    verify(store, never()).consume(anyLong(), anyString());
  }

  @Test
  void refreshDeletedLockedOrMissingUserIs401() {
    TokenServiceImpl service = serviceAt(Instant.now());
    TokenPair pair = service.issue(user());
    when(store.consume(eq(42L), anyString())).thenReturn(true);

    assertInvalidToken(() -> service.refresh(pair.refreshToken())); // user missing

    User deleted = user();
    deleted.setStatus(UserStatus.DELETED);
    when(userMapper.findById(42L)).thenReturn(deleted);
    assertInvalidToken(() -> service.refresh(pair.refreshToken()));

    User locked = user();
    locked.setStatus(UserStatus.LOCKED);
    when(userMapper.findById(42L)).thenReturn(locked);
    assertInvalidToken(() -> service.refresh(pair.refreshToken()));
  }

  @Test
  void revokeDeletesOwnTokenKey() {
    TokenServiceImpl service = serviceAt(Instant.now());
    TokenPair pair = service.issue(user());
    String jti = refreshDecoder.decode(pair.refreshToken()).getId();

    service.revoke(42L, pair.refreshToken());

    verify(store).revoke(42L, jti);
  }

  @Test
  void revokeOtherUsersTokenIs401AndDeletesNothing() {
    TokenServiceImpl service = serviceAt(Instant.now());
    TokenPair pair = service.issue(user());

    assertInvalidToken(() -> service.revoke(43L, pair.refreshToken()));

    verify(store, never()).revoke(anyLong(), anyString());
  }

  @Test
  void revokeGarbageTokenIs401() {
    assertInvalidToken(() -> serviceAt(Instant.now()).revoke(42L, "garbage"));
  }

  @Test
  void revokeAllDelegatesToStore() {
    serviceAt(Instant.now()).revokeAll(42L);

    ArgumentCaptor<Long> id = ArgumentCaptor.forClass(Long.class);
    verify(store).revokeAll(id.capture());
    assertThat(id.getValue()).isEqualTo(42L);
  }
}
