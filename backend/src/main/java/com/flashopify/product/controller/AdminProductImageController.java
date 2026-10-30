package com.flashopify.product.controller;

import com.flashopify.product.dto.AddProductImageRequest;
import com.flashopify.product.dto.ImageUploadUrlRequest;
import com.flashopify.product.dto.ImageUploadUrlResponse;
import com.flashopify.product.dto.ProductImageResponse;
import com.flashopify.product.dto.ReorderImagesRequest;
import com.flashopify.product.service.ProductImageService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin product gallery; the ADMIN role is enforced for {@code /api/v1/admin/**}. */
@RestController
@RequestMapping("/api/v1/admin/products/{productId}/images")
@RequiredArgsConstructor
public class AdminProductImageController {

  private final ProductImageService service;

  @PostMapping("/upload-url")
  public ImageUploadUrlResponse uploadUrl(
      @PathVariable long productId, @Valid @RequestBody ImageUploadUrlRequest request) {
    return service.createUploadUrl(productId, request);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProductImageResponse add(
      @PathVariable long productId, @Valid @RequestBody AddProductImageRequest request) {
    return service.addImage(productId, request);
  }

  @PutMapping
  public List<ProductImageResponse> reorder(
      @PathVariable long productId, @Valid @RequestBody ReorderImagesRequest request) {
    return service.reorder(productId, request);
  }

  @DeleteMapping("/{imageId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable long productId, @PathVariable long imageId) {
    service.deleteImage(productId, imageId);
  }
}
