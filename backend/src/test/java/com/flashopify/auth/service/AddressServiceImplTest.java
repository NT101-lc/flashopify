package com.flashopify.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashopify.auth.domain.Address;
import com.flashopify.auth.domain.User;
import com.flashopify.auth.domain.UserStatus;
import com.flashopify.auth.dto.AddressRequest;
import com.flashopify.auth.dto.AddressResponse;
import com.flashopify.auth.mapper.AddressMapper;
import com.flashopify.auth.mapper.UserMapper;
import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddressServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-10-27T08:00:00Z");
  private static final long USER = 7L;

  @Mock AddressMapper addressMapper;
  @Mock UserMapper userMapper;

  AddressServiceImpl service;

  @BeforeEach
  void setUp() {
    when(userMapper.lockForUpdate(USER))
        .thenReturn(User.builder().id(USER).status(UserStatus.ACTIVE).build());
    when(addressMapper.insert(any()))
        .thenAnswer(
            inv -> {
              inv.<Address>getArgument(0).setId(50L);
              return 1;
            });
    service = new AddressServiceImpl(addressMapper, userMapper, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static AddressRequest request(Boolean isDefault) {
    return new AddressRequest(
        " An ", "0901234567", "12 Nguyen Hue", "Ben Nghe", "Quan 1", "Ho Chi Minh", isDefault);
  }

  private static Address row(long id, boolean isDefault) {
    return Address.builder()
        .id(id)
        .userId(USER)
        .receiverName("An")
        .phone("0901234567")
        .addressLine("x")
        .city("c")
        .defaultAddress(isDefault)
        .createdAt(NOW)
        .build();
  }

  @Test
  void firstAddressBecomesDefaultEvenWhenNotRequested() {
    when(addressMapper.countByUser(USER)).thenReturn(0);
    when(addressMapper.findByIdAndUser(50L, USER)).thenReturn(row(50L, true));

    AddressResponse response = service.create(USER, request(false));

    ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
    verify(addressMapper).insert(captor.capture());
    assertThat(captor.getValue().isDefaultAddress()).isTrue();
    assertThat(captor.getValue().getUserId()).isEqualTo(USER);
    assertThat(captor.getValue().getReceiverName()).isEqualTo("An");
    assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW);
    assertThat(response.isDefault()).isTrue();
  }

  @Test
  void createLocksUserThenClearsOldDefaultBeforeInsertingNewDefault() {
    when(addressMapper.countByUser(USER)).thenReturn(2);
    when(addressMapper.findByIdAndUser(50L, USER)).thenReturn(row(50L, true));

    service.create(USER, request(true));

    InOrder order = inOrder(userMapper, addressMapper);
    order.verify(userMapper).lockForUpdate(USER);
    order.verify(addressMapper).clearDefault(USER);
    order.verify(addressMapper).insert(any());
  }

  @Test
  void createNonDefaultKeepsExistingDefault() {
    when(addressMapper.countByUser(USER)).thenReturn(2);
    when(addressMapper.findByIdAndUser(50L, USER)).thenReturn(row(50L, false));

    service.create(USER, request(null));

    verify(addressMapper, never()).clearDefault(USER);
    ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
    verify(addressMapper).insert(captor.capture());
    assertThat(captor.getValue().isDefaultAddress()).isFalse();
  }

  @Test
  void createDuplicateKeyMapsToConcurrentModification() {
    when(addressMapper.countByUser(USER)).thenReturn(1);
    org.mockito.Mockito.doThrow(new DuplicateKeyException("dup")).when(addressMapper).insert(any());

    assertThatThrownBy(() -> service.create(USER, request(true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
  }

  @Test
  void createForDeletedUserIs401() {
    when(userMapper.lockForUpdate(USER))
        .thenReturn(User.builder().id(USER).status(UserStatus.DELETED).build());

    assertThatThrownBy(() -> service.create(USER, request(true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    verify(addressMapper, never()).insert(any());
  }

  @Test
  void createForMissingUserIs401() {
    when(userMapper.lockForUpdate(USER)).thenReturn(null);

    assertThatThrownBy(() -> service.create(USER, request(true)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void updateOfForeignOrMissingAddressIs404() {
    when(addressMapper.findByIdAndUser(9L, USER)).thenReturn(null);

    assertThatThrownBy(() -> service.update(USER, 9L, request(true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ADDRESS_NOT_FOUND));
    verify(addressMapper, never()).update(any());
  }

  @Test
  void updatePromotingToDefaultClearsOldDefaultFirst() {
    when(addressMapper.findByIdAndUser(9L, USER)).thenReturn(row(9L, false));

    service.update(USER, 9L, request(true));

    InOrder order = inOrder(addressMapper);
    order.verify(addressMapper).clearDefault(USER);
    order.verify(addressMapper).update(any());
  }

  @Test
  void updateNeverUnsetsTheOnlyDefault() {
    when(addressMapper.findByIdAndUser(9L, USER)).thenReturn(row(9L, true));

    service.update(USER, 9L, request(false));

    ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
    verify(addressMapper).update(captor.capture());
    assertThat(captor.getValue().isDefaultAddress()).isTrue();
    assertThat(captor.getValue().getId()).isEqualTo(9L);
    verify(addressMapper, never()).clearDefault(USER);
  }

  @Test
  void deleteOfMissingAddressIs404() {
    when(addressMapper.findByIdAndUser(9L, USER)).thenReturn(null);

    assertThatThrownBy(() -> service.delete(USER, 9L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ADDRESS_NOT_FOUND));
  }

  @Test
  void deleteDefaultPromotesNewestRemaining() {
    when(addressMapper.findByIdAndUser(9L, USER)).thenReturn(row(9L, true));
    when(addressMapper.deleteByIdAndUser(9L, USER)).thenReturn(1);
    when(addressMapper.findNewestByUser(USER)).thenReturn(row(4L, false));

    service.delete(USER, 9L);

    verify(addressMapper).setDefault(4L, USER);
  }

  @Test
  void deleteDefaultWithNothingLeftPromotesNothing() {
    when(addressMapper.findByIdAndUser(9L, USER)).thenReturn(row(9L, true));
    when(addressMapper.deleteByIdAndUser(9L, USER)).thenReturn(1);
    when(addressMapper.findNewestByUser(USER)).thenReturn(null);

    service.delete(USER, 9L);

    verify(addressMapper, never())
        .setDefault(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void deleteNonDefaultDoesNotTouchDefault() {
    when(addressMapper.findByIdAndUser(9L, USER)).thenReturn(row(9L, false));
    when(addressMapper.deleteByIdAndUser(9L, USER)).thenReturn(1);

    service.delete(USER, 9L);

    verify(addressMapper, never()).findNewestByUser(USER);
  }

  @Test
  void listMapsRows() {
    when(addressMapper.findAllByUser(USER)).thenReturn(List.of(row(1L, true), row(2L, false)));

    assertThat(service.list(USER)).extracting(AddressResponse::id).containsExactly(1L, 2L);
  }
}
