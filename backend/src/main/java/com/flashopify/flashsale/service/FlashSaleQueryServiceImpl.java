package com.flashopify.flashsale.service;

import com.flashopify.flashsale.mapper.FlashSaleItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FlashSaleQueryServiceImpl implements FlashSaleQueryService {

  private final FlashSaleItemMapper itemMapper;

  @Override
  public boolean isProductInActiveCampaign(long productId) {
    return itemMapper.existsInActiveCampaign(productId) != null;
  }
}
