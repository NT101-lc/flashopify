package com.flashopify.auth.service;

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
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TokenServiceImpl implements TokenService {

  private final JwtEncoder jwtEncoder;
  private final JwtDecoder refreshTokenDecoder;
  private final JwtProperties props;
  private final Clock clock;
  private final RefreshTokenStore refreshTokenStore;
  private final UserMapper userMapper;

  public TokenServiceImpl(
      JwtEncoder jwtEncoder,
      @Qualifier("refreshTokenDecoder") JwtDecoder refreshTokenDecoder,
      JwtProperties props,
      Clock clock,
      RefreshTokenStore refreshTokenStore,
      UserMapper userMapper) {
    this.jwtEncoder = jwtEncoder;
    this.refreshTokenDecoder = refreshTokenDecoder;
    this.props = props;
    this.clock = clock;
    this.refreshTokenStore = refreshTokenStore;
    this.userMapper = userMapper;
  }

  @Override
  public TokenPair issue(User user) {
    Instant now = clock.instant();
    Jwt access = sign(user, JwtConfig.TYPE_ACCESS, now, props.accessTtl());
    Jwt refresh = sign(user, JwtConfig.TYPE_REFRESH, now, props.refreshTtl());
    refreshTokenStore.save(user.getId(), refresh.getId(), props.refreshTtl());
    return new TokenPair(
        access.getTokenValue(), refresh.getTokenValue(), props.accessTtl().toSeconds());
  }

  @Override
  public RefreshedSession refresh(String refreshToken) {
    Jwt jwt = decodeRefresh(refreshToken);
    long userId = subject(jwt);
    if (!refreshTokenStore.consume(userId, jwt.getId())) {
      // Valid signature but no live key: already rotated, logged out or revoked -> assume theft.
      refreshTokenStore.revokeAll(userId);
      log.warn("Refresh token reuse detected, revoked all sessions of user {}", userId);
      throw invalidToken();
    }
    User user = userMapper.findById(userId);
    if (user == null
        || user.getStatus() == UserStatus.DELETED
        || user.getStatus() == UserStatus.LOCKED) {
      throw invalidToken();
    }
    return new RefreshedSession(user, issue(user));
  }

  @Override
  public void revoke(long userId, String refreshToken) {
    Jwt jwt = decodeRefresh(refreshToken);
    if (subject(jwt) != userId) {
      throw invalidToken();
    }
    refreshTokenStore.revoke(userId, jwt.getId());
  }

  @Override
  public void revokeAll(long userId) {
    refreshTokenStore.revokeAll(userId);
  }

  private Jwt decodeRefresh(String token) {
    try {
      Jwt jwt = refreshTokenDecoder.decode(token);
      if (jwt.getId() == null) {
        throw invalidToken();
      }
      return jwt;
    } catch (JwtException e) {
      throw invalidToken();
    }
  }

  private static long subject(Jwt jwt) {
    try {
      return Long.parseLong(jwt.getSubject());
    } catch (NumberFormatException e) {
      throw invalidToken();
    }
  }

  private static BusinessException invalidToken() {
    return new BusinessException(ErrorCode.INVALID_TOKEN, "Invalid or expired refresh token");
  }

  private Jwt sign(User user, String type, Instant now, Duration ttl) {
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(props.issuer())
            .subject(String.valueOf(user.getId()))
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .claim(JwtConfig.CLAIM_TYPE, type);
    if (JwtConfig.TYPE_ACCESS.equals(type)) {
      claims.claim(JwtConfig.CLAIM_ROLE, user.getRole().name());
    }
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build()));
  }
}
