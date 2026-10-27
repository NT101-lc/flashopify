package com.flashopify.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors RefreshTokenRequest in openapi.yaml. */
public record RefreshTokenRequest(@NotBlank String refreshToken) {}
