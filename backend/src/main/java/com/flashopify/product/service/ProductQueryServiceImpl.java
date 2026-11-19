package com.flashopify.product.service;

import com.flashopify.product.domain.Product;
import com.flashopify.product.dto.ProductSnapshot;
import com.flashopify.product.mapper.ProductMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductQueryServiceImpl implements ProductQueryService {

  private final ProductMapper productMapper;

  @Override
  public Map<Long, ProductSnapshot> findByIds(Collection<Long> productIds) {
    if (productIds == null || productIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, ProductSnapshot> result = new HashMap<>();
    for (Product p : productMapper.findByIds(new TreeSet<>(productIds))) {
      result.put(p.getId(), toSnapshot(p));
    }
    return result;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Map<Long, ProductSnapshot> lockForShare(Collection<Long> productIds) {
    Map<Long, ProductSnapshot> result = new LinkedHashMap<>();
    // One statement per row in ascending order: the lock acquisition order is then explicit
    // instead of depending on the optimizer's scan order for an IN list.
    for (Long id : new TreeSet<>(productIds)) {
      Product p = productMapper.lockByIdShared(id);
      if (p != null) {
        result.put(id, toSnapshot(p));
      }
    }
    return result;
  }

  private static ProductSnapshot toSnapshot(Product p) {
    return new ProductSnapshot(
        p.getId(), p.getName(), p.getSlug(), p.getPrice(), p.getThumbnailUrl(), p.getStatus());
  }
}
