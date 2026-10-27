package com.flashopify.auth.service;

import com.flashopify.auth.dto.LoginRequest;
import com.flashopify.auth.dto.RegisterRequest;
import com.flashopify.auth.dto.TokenResponse;
import com.flashopify.auth.dto.UserProfileResponse;

/** Registration and credential login. */
public interface AuthService {

  UserProfileResponse register(RegisterRequest request);

  TokenResponse login(LoginRequest request);
}
