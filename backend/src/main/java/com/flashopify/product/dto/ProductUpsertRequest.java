package com.flashopify.product.dto;

import com.flashopify.product.domain.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Mirrors {@code ProductUpsertRequest}. */
public record ProductUpsertRequest(
    @NotNull Long categoryId,
    @NotBlank @Size(max = 50) String sku,
    @NotBlank @Size(max = 255) String name,
    @NotBlank
        @Size(max = 255)
        @Pattern.List({
          @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "must be a lowercase slug"),
          @Pattern(regexp = "^(?!brands$).*", message = "'brands' is a reserved slug")
        })
        String slug,
    @Size(max = 16000) String description,
    @Size(max = 100) String brand,
    @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal price,
    @NotNull @Pattern(regexp = "ACTIVE|INACTIVE", message = "must be ACTIVE or INACTIVE")
        String status,
    @Min(0) Integer initialStock) {

  public ProductStatus productStatus() {
    return ProductStatus.valueOf(status);
  }
}
