package com.flashopify.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Mirrors ChangePasswordRequest in openapi.yaml. */
public record ChangePasswordRequest(
    @NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {}
