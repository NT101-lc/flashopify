package com.flashopify.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Mirrors RegisterRequest in openapi.yaml. */
public record RegisterRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(min = 8, max = 72) String password,
    @NotBlank @Size(max = 100) String fullName,
    @Pattern(regexp = "^\\+?\\d{9,15}$") String phone) {}
