package com.flashopify.flashsale.mapper;

import com.flashopify.flashsale.domain.Campaign;
import com.flashopify.flashsale.domain.CampaignSearchCriteria;
import com.flashopify.flashsale.domain.CampaignStatus;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for flash-sale campaigns; module-private. */
@Mapper
public interface CampaignMapper {

  /** Inserts with status SCHEDULED and fills the generated id. */
  int insert(Campaign campaign);

  /** Campaign with its item count and total stock; null when absent. */
  Campaign findSummaryById(@Param("id") long id);

  /** {@code SELECT ... FOR UPDATE} on the campaign row only (no aggregates). */
  Campaign lockById(@Param("id") long id);

  /** Updates name and time window. */
  int updateHeader(Campaign campaign);

  int updateStatus(@Param("id") long id, @Param("status") CampaignStatus status);

  List<Campaign> search(@Param("c") CampaignSearchCriteria criteria);

  long count(@Param("c") CampaignSearchCriteria criteria);
}
