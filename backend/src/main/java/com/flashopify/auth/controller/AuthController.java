package com.flashopify.auth.controller;

import com.flashopify.auth.dto.LoginRequest;
import com.flashopify.auth.dto.RefreshTokenRequest;
import com.flashopify.auth.dto.RegisterRequest;
import com.flashopify.auth.dto.TokenResponse;
import com.flashopify.auth.dto.UserProfileResponse;
import com.flashopify.auth.service.AuthService;
import com.flashopify.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public UserProfileResponse register(@Valid @RequestBody RegisterRequest request) {
    return authService.register(request);
  }

  @PostMapping("/login")
  public TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request);
  }

  @PostMapping("/refresh")
  public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return authService.refresh(request.refreshToken());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RefreshTokenRequest request) {
    authService.logout(CurrentUser.id(jwt), request.refreshToken());
  }
}
