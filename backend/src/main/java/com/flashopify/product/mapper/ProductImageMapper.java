package com.flashopify.product.mapper;

import com.flashopify.product.domain.ProductImage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for product images; module-private. */
@Mapper
public interface ProductImageMapper {

  List<ProductImage> findByProductId(@Param("productId") long productId);

  int countByProductId(@Param("productId") long productId);

  /** Inserts and fills the generated id. */
  int insert(ProductImage image);

  /** Null when the image does not exist or belongs to another product. */
  ProductImage findByIdAndProduct(@Param("id") long id, @Param("productId") long productId);

  int deleteByIdAndProduct(@Param("id") long id, @Param("productId") long productId);

  int updateSortOrder(
      @Param("id") long id, @Param("productId") long productId, @Param("sortOrder") int sortOrder);

  /** Moves every image with sort_order {@code >= fromOrder} one position up (+1). */
  int shiftFrom(@Param("productId") long productId, @Param("fromOrder") int fromOrder);
}
