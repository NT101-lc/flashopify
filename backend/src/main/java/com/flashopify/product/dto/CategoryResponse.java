package com.flashopify.product.dto;

import java.util.List;

/** Mirrors {@code Category}; {@code productCount} includes descendants. */
public record CategoryResponse(
    long id, String slug, String name, long productCount, List<CategoryResponse> children) {}
