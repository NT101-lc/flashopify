package com.flashopify.inventory.service;

import com.flashopify.inventory.domain.Inventory;
import com.flashopify.inventory.dto.InventoryView;
import com.flashopify.inventory.mapper.InventoryMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

  private final InventoryMapper inventoryMapper;

  @Override
  public Map<Long, InventoryView> findByProductIds(Collection<Long> productIds) {
    if (productIds == null || productIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, InventoryView> result = new HashMap<>();
    for (Inventory inv : inventoryMapper.findByProductIds(productIds)) {
      result.put(inv.getProductId(), toView(inv));
    }
    return result;
  }

  @Override
  public int availableOf(long productId) {
    Inventory inv = inventoryMapper.findByProductId(productId);
    return inv == null ? 0 : Math.max(0, inv.getQuantity() - inv.getReserved());
  }

  private static InventoryView toView(Inventory inv) {
    return InventoryView.of(inv.getQuantity(), inv.getReserved(), inv.getVersion());
  }
}
