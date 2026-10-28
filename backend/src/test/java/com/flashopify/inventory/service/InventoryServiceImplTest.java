package com.flashopify.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashopify.inventory.domain.Inventory;
import com.flashopify.inventory.dto.InventoryView;
import com.flashopify.inventory.mapper.InventoryMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

  @Mock InventoryMapper mapper;
  @InjectMocks InventoryServiceImpl service;

  @Test
  void missingRowIsZeroAvailable() {
    when(mapper.findByProductId(5L)).thenReturn(null);
    assertThat(service.availableOf(5L)).isZero();
  }

  @Test
  void availableIsQuantityMinusReserved() {
    when(mapper.findByProductId(5L)).thenReturn(new Inventory(5L, 10, 3, 1, null));
    assertThat(service.availableOf(5L)).isEqualTo(7);
  }

  @Test
  void emptyInputDoesNotQuery() {
    assertThat(service.findByProductIds(List.of())).isEmpty();
    verifyNoInteractions(mapper);
  }

  @Test
  void batchMapsViewsById() {
    when(mapper.findByProductIds(List.of(1L, 2L)))
        .thenReturn(List.of(new Inventory(1L, 10, 4, 2, null)));
    Map<Long, InventoryView> m = service.findByProductIds(List.of(1L, 2L));
    assertThat(m).containsOnlyKeys(1L);
    assertThat(m.get(1L)).isEqualTo(new InventoryView(10, 4, 6, 2));
  }
}
