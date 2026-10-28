package com.flashopify.product.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Mirrors {@code ReorderImagesRequest}; must list every image of the product exactly once. */
public record ReorderImagesRequest(@NotNull @Size(max = 8) List<@NotNull Long> imageIds) {}
