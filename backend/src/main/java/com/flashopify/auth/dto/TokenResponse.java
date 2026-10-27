package com.flashopify.auth.dto;

/** Mirrors TokenResponse in openapi.yaml. */
public record TokenResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    UserProfileResponse user) {

  public static final String BEARER = "Bearer";
}
