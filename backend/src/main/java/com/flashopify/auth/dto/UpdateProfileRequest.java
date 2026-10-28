package com.flashopify.auth.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Mirrors UpdateProfileRequest in openapi.yaml; null fields are left unchanged. */
public record UpdateProfileRequest(
    @Size(min = 1, max = 100) String fullName, @Pattern(regexp = "^\\+?\\d{9,15}$") String phone) {}
