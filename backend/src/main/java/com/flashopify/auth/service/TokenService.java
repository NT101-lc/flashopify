package com.flashopify.auth.service;

import com.flashopify.auth.domain.User;

/** Issues signed JWTs (ADR-0007). */
public interface TokenService {

  TokenPair issue(User user);
}
