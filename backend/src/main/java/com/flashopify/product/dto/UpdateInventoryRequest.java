package com.flashopify.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Mirrors {@code UpdateInventoryRequest}. */
public record UpdateInventoryRequest(@NotNull @Min(0) Integer quantity, @NotNull Integer version) {}
