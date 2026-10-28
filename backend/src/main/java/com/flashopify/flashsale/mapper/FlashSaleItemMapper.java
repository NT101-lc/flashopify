package com.flashopify.flashsale.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for flash-sale items; module-private. */
@Mapper
public interface FlashSaleItemMapper {

  /** Locking read (FOR SHARE); returns 1 when found, null otherwise. */
  Integer existsInActiveCampaign(@Param("productId") long productId);
}
