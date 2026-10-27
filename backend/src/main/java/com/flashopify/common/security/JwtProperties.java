package com.flashopify.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT settings (ADR-0007). The HS256 secret must be at least 32 bytes. */
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTtl, Duration refreshTtl) {

  private static final int MIN_SECRET_BYTES = 32;

  public JwtProperties {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "app.security.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes");
    }
  }
}
