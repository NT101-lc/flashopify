package com.flashopify.admin.seed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Entry of DummyJSON {@code GET /products/categories}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DummyJsonCategory(String slug, String name, String url) {}
