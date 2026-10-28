package com.flashopify.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
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
import org.springframework.dao.DataIntegrityViolationException;

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

  @Test
  void updateQuantityReturnsReReadView() {
    when(mapper.updateQuantity(5L, 20, 3)).thenReturn(1);
    when(mapper.findByProductId(5L)).thenReturn(new Inventory(5L, 20, 4, 4, null));
    assertThat(service.updateQuantity(5L, 20, 3)).isEqualTo(new InventoryView(20, 4, 16, 4));
  }

  @Test
  void updateQuantityStaleVersionIsConcurrentModification() {
    when(mapper.updateQuantity(5L, 20, 3)).thenReturn(0);
    when(mapper.findByProductIdForShare(5L)).thenReturn(new Inventory(5L, 20, 4, 4, null));
    assertThat(codeOf(() -> service.updateQuantity(5L, 20, 3)))
        .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
  }

  @Test
  void updateQuantityBelowReservedIsInsufficientStock() {
    when(mapper.updateQuantity(5L, 2, 4)).thenReturn(0);
    when(mapper.findByProductIdForShare(5L)).thenReturn(new Inventory(5L, 10, 4, 4, null));
    assertThat(codeOf(() -> service.updateQuantity(5L, 2, 4)))
        .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
  }

  @Test
  void updateQuantityWithoutRowIsNotFound() {
    when(mapper.updateQuantity(5L, 2, 0)).thenReturn(0);
    when(mapper.findByProductIdForShare(5L)).thenReturn(null);
    assertThat(codeOf(() -> service.updateQuantity(5L, 2, 0)))
        .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
  }

  @Test
  void checkViolationNeverEscapesAsServerError() {
    when(mapper.updateQuantity(5L, 2, 0)).thenThrow(new DataIntegrityViolationException("chk"));
    assertThat(codeOf(() -> service.updateQuantity(5L, 2, 0)))
        .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
  }

  private static ErrorCode codeOf(Runnable call) {
    try {
      call.run();
    } catch (BusinessException e) {
      return e.getCode();
    }
    throw new AssertionError("expected BusinessException");
  }
}
