package com.flashopify.common.web;

import java.util.List;

/** Generic page; mirrors {@code PageResponse} in openapi.yaml. {@code page} is 0-based. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

  public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
    int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + size - 1) / size);
    return new PageResponse<>(items, page, size, totalItems, totalPages);
  }
}
