package com.flashopify.product.mapper;

import com.flashopify.product.domain.AdminProductSearchCriteria;
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

  // ---- admin side: any status ----

  /**
   * Inserts and fills the generated id. Throws DuplicateKeyException on uk_products_sku /
   * uk_products_slug / uk_products_source.
   */
  int insert(Product product);

  /** Updates the editable columns (not source_id, created_at). */
  int update(Product product);

  /** Any status, with the category join. */
  Product findById(@Param("id") long id);

  /** {@code SELECT ... FOR UPDATE} on the product row only (no joins); any status. */
  Product lockById(@Param("id") long id);

  /** {@code SELECT ... FOR SHARE} on the product row only; any status. */
  Product lockByIdShared(@Param("id") long id);

  /** Sets (or clears with null) the thumbnail URL. */
  int updateThumbnail(@Param("id") long id, @Param("url") String url);

  int softDelete(@Param("id") long id);

  /** Reads inventory with a LEFT JOIN for sort/filter only (ADR-0009); never writes it. */
  List<Product> adminSearch(@Param("c") AdminProductSearchCriteria criteria);

  long adminCount(@Param("c") AdminProductSearchCriteria criteria);

  boolean existsBySourceId(@Param("sourceId") int sourceId);

  /** Number of products whose thumbnail URL contains the given text (seed hotlink check). */
  long countByThumbnailContaining(@Param("text") String text);
}
