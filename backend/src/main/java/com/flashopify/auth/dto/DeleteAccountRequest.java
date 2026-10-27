package com.flashopify.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors DeleteAccountRequest in openapi.yaml. */
public record DeleteAccountRequest(@NotBlank String password) {}
