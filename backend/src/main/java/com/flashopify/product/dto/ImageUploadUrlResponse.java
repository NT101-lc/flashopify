package com.flashopify.product.dto;

import java.time.Instant;
import java.util.Map;

/** Mirrors {@code ImageUploadUrlResponse}; {@code method} is always PUT. */
public record ImageUploadUrlResponse(
    String uploadUrl,
    String objectKey,
    String method,
    Map<String, String> headers,
    Instant expiresAt) {}
