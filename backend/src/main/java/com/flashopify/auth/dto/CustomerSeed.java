package com.flashopify.auth.dto;

/** One synthetic customer for the seed job; the address becomes the user's default address. */
public record CustomerSeed(String email, String fullName, String phone, AddressSeed address) {

  /** Address columns of a seeded customer; receiver name and phone come from the customer. */
  public record AddressSeed(String addressLine, String ward, String district, String city) {}
}
