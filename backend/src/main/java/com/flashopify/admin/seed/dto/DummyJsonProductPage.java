package com.flashopify.admin.seed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Page envelope of DummyJSON {@code GET /products?limit=&skip=}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DummyJsonProductPage(
    List<DummyJsonProduct> products, int total, int skip, int limit) {}
