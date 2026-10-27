package com.flashopify.auth.service;

import com.flashopify.auth.dto.AddressRequest;
import com.flashopify.auth.dto.AddressResponse;
import java.util.List;

/** Shipping addresses of one user; every method is scoped by the caller's id. */
public interface AddressService {

  /** Default address first. */
  List<AddressResponse> list(long userId);

  /** The first address is always the default; isDefault=true demotes the previous default. */
  AddressResponse create(long userId, AddressRequest request);

  /** 404 ADDRESS_NOT_FOUND when the address does not exist or belongs to someone else. */
  AddressResponse update(long userId, long addressId, AddressRequest request);

  /** Deleting the default promotes the newest remaining address. */
  void delete(long userId, long addressId);
}
