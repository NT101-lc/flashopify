package com.flashopify.product.mapper;

import com.flashopify.product.domain.Product;
import com.flashopify.product.domain.ProductSearchCriteria;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for products; module-private. Public queries only ever return ACTIVE rows. */
@Mapper
public interface ProductMapper {

  List<Product> searchActive(@Param("c") ProductSearchCriteria criteria);

  long countActive(@Param("c") ProductSearchCriteria criteria);

  Product findActiveBySlug(@Param("slug") String slug);

  /** Distinct non-null brands of ACTIVE products, alphabetical; null categoryIds = all. */
  List<String> findDistinctActiveBrands(@Param("categoryIds") Collection<Long> categoryIds);
}
