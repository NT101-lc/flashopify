package com.flashopify.product.mapper;

import com.flashopify.product.domain.ProductImage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for product images; module-private. */
@Mapper
public interface ProductImageMapper {

  List<ProductImage> findByProductId(@Param("productId") long productId);
}
