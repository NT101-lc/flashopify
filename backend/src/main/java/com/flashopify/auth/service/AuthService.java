package com.flashopify.auth.service;

import com.flashopify.auth.dto.LoginRequest;
import com.flashopify.auth.dto.RegisterRequest;
import com.flashopify.auth.dto.TokenResponse;
import com.flashopify.auth.dto.UserProfileResponse;

/** Registration and credential login. */
public interface AuthService {

  UserProfileResponse register(RegisterRequest request);

  TokenResponse login(LoginRequest request);

  /** Rotates the refresh token (single-use) and returns a fresh token pair. */
  TokenResponse refresh(String refreshToken);

  /** Revokes the given refresh token; it must belong to the authenticated user. */
  void logout(long userId, String refreshToken);

  /** Verifies the current password, stores the new one and revokes all refresh tokens. */
  void changePassword(long userId, String currentPassword, String newPassword);
}
