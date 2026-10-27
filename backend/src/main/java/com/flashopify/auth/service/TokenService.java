package com.flashopify.auth.service;

import com.flashopify.auth.domain.User;

/** Issues, rotates and revokes JWTs (ADR-0007). */
public interface TokenService {

  /** Signs a new access/refresh pair and whitelists the refresh jti in Redis. */
  TokenPair issue(User user);

  /**
   * Single-use rotation. Invalid/expired token or unknown user gives 401 INVALID_TOKEN; reuse of an
   * already consumed token additionally revokes every refresh token of that user.
   */
  RefreshedSession refresh(String refreshToken);

  /** Revokes one refresh token; 401 INVALID_TOKEN if it is invalid or belongs to another user. */
  void revoke(long userId, String refreshToken);

  void revokeAll(long userId);
}
