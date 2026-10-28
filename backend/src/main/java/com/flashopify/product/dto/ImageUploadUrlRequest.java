package com.flashopify.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Mirrors {@code ImageUploadUrlRequest}. The content type whitelist and the size limit are checked
 * by the service so they produce {@code UNSUPPORTED_MEDIA_TYPE} / {@code FILE_TOO_LARGE}.
 */
public record ImageUploadUrlRequest(
    @NotBlank @Size(max = 255) String fileName,
    @NotBlank String contentType,
    @NotNull @Positive Long contentLength) {}
