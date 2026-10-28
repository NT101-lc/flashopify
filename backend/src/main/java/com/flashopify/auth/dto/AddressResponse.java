package com.flashopify.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flashopify.auth.domain.Address;
import java.time.Instant;

/** Mirrors Address in openapi.yaml. */
public record AddressResponse(
    long id,
    String receiverName,
    String phone,
    String addressLine,
    String ward,
    String district,
    String city,
    @JsonProperty("isDefault") boolean isDefault,
    Instant createdAt) {

  public static AddressResponse from(Address a) {
    return new AddressResponse(
        a.getId(),
        a.getReceiverName(),
        a.getPhone(),
        a.getAddressLine(),
        a.getWard(),
        a.getDistrict(),
        a.getCity(),
        a.isDefaultAddress(),
        a.getCreatedAt());
  }
}
