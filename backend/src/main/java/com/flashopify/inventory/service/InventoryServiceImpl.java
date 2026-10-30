package com.flashopify.inventory.service;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.inventory.domain.Inventory;
import com.flashopify.inventory.dto.InventoryView;
import com.flashopify.inventory.mapper.InventoryMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  @Override
  @Transactional
  public void initialize(long productId, int quantity) {
    inventoryMapper.insert(productId, quantity);
  }

  @Override
  @Transactional
  public InventoryView updateQuantity(long productId, int quantity, int expectedVersion) {
    int updated;
    try {
      updated = inventoryMapper.updateQuantity(productId, quantity, expectedVersion);
    } catch (DataIntegrityViolationException e) {
      // Defensive: a CHECK violation (e.g. a concurrent writer breaking the invariant) is a
      // conflict for the caller, never a 500.
      throw new BusinessException(
          ErrorCode.INSUFFICIENT_STOCK, "Quantity violates the stock constraints");
    }
    if (updated == 0) {
      // The guarded UPDATE matched nothing; re-read to tell the three reasons apart. The WHERE
      // clause already enforces reserved <= quantity, so the CHECK constraint is never reached.
      Inventory current = inventoryMapper.findByProductIdForShare(productId);
      if (current == null) {
        throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Inventory not found");
      }
      if (current.getVersion() != expectedVersion) {
        throw new BusinessException(
            ErrorCode.CONCURRENT_MODIFICATION, "Stock was changed by someone else, reload");
      }
      throw new BusinessException(
          ErrorCode.INSUFFICIENT_STOCK,
          "Quantity cannot be lower than the reserved quantity (" + current.getReserved() + ")");
    }
    return toView(inventoryMapper.findByProductId(productId));
  }

  private static InventoryView toView(Inventory inv) {
    return InventoryView.of(inv.getQuantity(), inv.getReserved(), inv.getVersion());
  }
}
