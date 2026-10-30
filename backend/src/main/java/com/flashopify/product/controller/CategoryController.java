package com.flashopify.product.controller;

import com.flashopify.product.dto.CategoryResponse;
import com.flashopify.product.service.CatalogService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public category tree. */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

  private final CatalogService catalogService;

  @GetMapping
  public List<CategoryResponse> tree() {
    return catalogService.categoryTree();
  }
}
