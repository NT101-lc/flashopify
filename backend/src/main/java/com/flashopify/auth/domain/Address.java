package com.flashopify.auth.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Row of the addresses table. Owned by the auth module. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Address {

  private Long id;
  private Long userId;
  private String receiverName;
  private String phone;
  private String addressLine;
  private String ward;
  private String district;
  private String city;
  private boolean defaultAddress;
  private Instant createdAt;
}
