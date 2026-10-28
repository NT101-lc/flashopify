package com.flashopify.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** The only way controllers obtain the caller's id: always from the verified token, never input. */
public final class CurrentUser {

  private CurrentUser() {}

  public static long id(Jwt jwt) {
    return Long.parseLong(jwt.getSubject());
  }
}
