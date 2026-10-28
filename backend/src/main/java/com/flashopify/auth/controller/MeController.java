package com.flashopify.auth.controller;

import com.flashopify.auth.dto.ChangePasswordRequest;
import com.flashopify.auth.dto.DeleteAccountRequest;
import com.flashopify.auth.dto.UpdateProfileRequest;
import com.flashopify.auth.dto.UserProfileResponse;
import com.flashopify.auth.service.AuthService;
import com.flashopify.auth.service.UserService;
import com.flashopify.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

  private final AuthService authService;
  private final UserService userService;

  @GetMapping
  public UserProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
    return userService.getProfile(CurrentUser.id(jwt));
  }

  @PatchMapping
  public UserProfileResponse updateProfile(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
    return userService.updateProfile(CurrentUser.id(jwt), request);
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteAccount(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeleteAccountRequest request) {
    userService.deleteAccount(CurrentUser.id(jwt), request.password());
  }

  @PutMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
    authService.changePassword(
        CurrentUser.id(jwt), request.currentPassword(), request.newPassword());
  }
}
