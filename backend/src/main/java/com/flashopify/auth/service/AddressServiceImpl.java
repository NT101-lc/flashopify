package com.flashopify.auth.service;

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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes take a row lock on the owning user first, so address changes of one user are serialised
 * and the "one default per user" rule holds (the unique key on default_owner is the safety net).
 */
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

  private final AddressMapper addressMapper;
  private final UserMapper userMapper;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public List<AddressResponse> list(long userId) {
    return addressMapper.findAllByUser(userId).stream().map(AddressResponse::from).toList();
  }

  @Override
  @Transactional
  public AddressResponse create(long userId, AddressRequest request) {
    lockActiveUser(userId);
    boolean makeDefault =
        Boolean.TRUE.equals(request.isDefault()) || addressMapper.countByUser(userId) == 0;
    if (makeDefault) {
      addressMapper.clearDefault(userId);
    }
    Address address = toAddress(userId, request, makeDefault);
    address.setCreatedAt(clock.instant());
    try {
      addressMapper.insert(address);
    } catch (DuplicateKeyException e) {
      throw concurrentModification();
    }
    return AddressResponse.from(addressMapper.findByIdAndUser(address.getId(), userId));
  }

  @Override
  @Transactional
  public AddressResponse update(long userId, long addressId, AddressRequest request) {
    lockActiveUser(userId);
    Address existing = addressMapper.findByIdAndUser(addressId, userId);
    if (existing == null) {
      throw notFound();
    }
    // An update can promote an address but never leaves the user without a default.
    boolean makeDefault = Boolean.TRUE.equals(request.isDefault()) || existing.isDefaultAddress();
    if (makeDefault && !existing.isDefaultAddress()) {
      addressMapper.clearDefault(userId);
    }
    Address changed = toAddress(userId, request, makeDefault);
    changed.setId(addressId);
    try {
      addressMapper.update(changed);
    } catch (DuplicateKeyException e) {
      throw concurrentModification();
    }
    return AddressResponse.from(addressMapper.findByIdAndUser(addressId, userId));
  }

  @Override
  @Transactional
  public void delete(long userId, long addressId) {
    lockActiveUser(userId);
    Address existing = addressMapper.findByIdAndUser(addressId, userId);
    if (existing == null || addressMapper.deleteByIdAndUser(addressId, userId) == 0) {
      throw notFound();
    }
    if (existing.isDefaultAddress()) {
      Address newest = addressMapper.findNewestByUser(userId);
      if (newest != null) {
        addressMapper.setDefault(newest.getId(), userId);
      }
    }
  }

  private void lockActiveUser(long userId) {
    User user = userMapper.lockForUpdate(userId);
    if (user == null || user.getStatus() == UserStatus.DELETED) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
    }
  }

  private static Address toAddress(long userId, AddressRequest r, boolean isDefault) {
    return Address.builder()
        .userId(userId)
        .receiverName(r.receiverName().trim())
        .phone(r.phone())
        .addressLine(r.addressLine().trim())
        .ward(r.ward())
        .district(r.district())
        .city(r.city().trim())
        .defaultAddress(isDefault)
        .build();
  }

  private static BusinessException notFound() {
    return new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "Address not found");
  }

  private static BusinessException concurrentModification() {
    return new BusinessException(
        ErrorCode.CONCURRENT_MODIFICATION, "Addresses were modified concurrently, retry");
  }
}
