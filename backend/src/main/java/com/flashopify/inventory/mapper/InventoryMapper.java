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

  /** Locking read ({@code FOR SHARE}) used to classify a failed guarded update. */
  Inventory findByProductIdForShare(@Param("productId") long productId);

  List<Inventory> findByProductIds(@Param("productIds") Collection<Long> productIds);

  /** Creates the row with reserved 0 and version 0. */
  int insert(@Param("productId") long productId, @Param("quantity") int quantity);

  /**
   * Optimistic, guarded update: succeeds only when the version matches and the new quantity does
   * not drop below reserved. Always bumps the version. Returns affected rows (0 or 1).
   */
  int updateQuantity(
      @Param("productId") long productId,
      @Param("quantity") int quantity,
      @Param("expectedVersion") int expectedVersion);
}
