package com.flashopify.flashsale.mapper;

import com.flashopify.flashsale.domain.CampaignItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for flash-sale items; module-private. */
@Mapper
public interface FlashSaleItemMapper {

  /** Locking read (FOR SHARE); returns 1 when found, null otherwise. */
  Integer existsInActiveCampaign(@Param("productId") long productId);

  /** Plain (snapshot) read, ordered by product id. */
  List<CampaignItem> findByCampaignId(@Param("campaignId") long campaignId);

  /**
   * Locking read ({@code FOR UPDATE}) of the campaign's items, ordered by product id. Returns the
   * latest committed rows, unlike a plain read after the transaction's first snapshot.
   */
  List<CampaignItem> lockByCampaignId(@Param("campaignId") long campaignId);

  /** Inserts and fills the generated id. */
  int insert(CampaignItem item);

  /** Updates sale price, total stock and limit per user. */
  int update(CampaignItem item);

  int updateSold(@Param("id") long id, @Param("sold") int sold);

  int deleteById(@Param("id") long id);
}
