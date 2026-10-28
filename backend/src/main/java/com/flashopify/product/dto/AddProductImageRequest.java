package com.flashopify.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Mirrors {@code AddProductImageRequest}; {@code sortOrder} defaults to the end of the gallery. */
public record AddProductImageRequest(
    @NotBlank @Size(max = 255) String objectKey,
    @Min(0) Integer sortOrder,
    boolean setAsThumbnail) {}
