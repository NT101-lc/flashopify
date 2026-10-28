package com.flashopify.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Mirrors AddressRequest in openapi.yaml. */
public record AddressRequest(
    @NotBlank @Size(max = 100) String receiverName,
    @NotBlank @Pattern(regexp = "^\\+?\\d{9,15}$") String phone,
    @NotBlank @Size(max = 255) String addressLine,
    @Size(max = 100) String ward,
    @Size(max = 100) String district,
    @NotBlank @Size(max = 100) String city,
    @JsonProperty("isDefault") Boolean isDefault) {}
