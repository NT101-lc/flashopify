package com.flashopify.auth.service;

import com.flashopify.auth.domain.User;
import com.flashopify.common.security.JwtConfig;
import com.flashopify.common.security.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

  private final JwtEncoder jwtEncoder;
  private final JwtProperties props;
  private final Clock clock;

  @Override
  public TokenPair issue(User user) {
    Instant now = clock.instant();
    String access = sign(user, JwtConfig.TYPE_ACCESS, now, props.accessTtl());
    String refresh = sign(user, JwtConfig.TYPE_REFRESH, now, props.refreshTtl());
    // TODO(FLS-006): store the refresh jti in Redis as rt:{userId}:{jti} with refreshTtl.
    return new TokenPair(access, refresh, props.accessTtl().toSeconds());
  }

  private String sign(User user, String type, Instant now, Duration ttl) {
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
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
  }
}
