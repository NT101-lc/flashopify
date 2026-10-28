package com.flashopify.inventory.mapper;

import com.flashopify.inventory.domain.Inventory;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for inventory; module-private. */
@Mapper
public interface InventoryMapper {

  Inventory findByProductId(@Param("productId") long productId);

  List<Inventory> findByProductIds(@Param("productIds") Collection<Long> productIds);
}
