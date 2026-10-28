package com.flashopify.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors LoginRequest in openapi.yaml. */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
