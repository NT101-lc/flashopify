package com.flashopify.product.mapper;

import com.flashopify.product.domain.Category;
import com.flashopify.product.domain.CategoryProductCount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for categories; module-private. */
@Mapper
public interface CategoryMapper {

  List<Category> findAll();

  Category findBySlug(@Param("slug") String slug);

  List<CategoryProductCount> countActiveProductsByCategory();
}
