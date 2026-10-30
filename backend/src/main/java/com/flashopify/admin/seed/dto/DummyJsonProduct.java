package com.flashopify.admin.seed.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Product of DummyJSON {@code GET /products}; only the fields the seed uses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DummyJsonProduct(
    int id,
    String title,
    String description,
    String category,
    Double price,
    Double rating,
    Integer stock,
    String brand,
    String sku,
    String thumbnail,
    List<String> images) {}
