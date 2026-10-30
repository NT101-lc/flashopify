package com.flashopify.product.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Row of the categories table. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {

  private Long id;
  private Long parentId;
  private String slug;
  private String name;
  private Instant createdAt;
}
