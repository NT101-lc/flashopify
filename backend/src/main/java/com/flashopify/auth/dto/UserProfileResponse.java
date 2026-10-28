package com.flashopify.auth.dto;

import com.flashopify.auth.domain.Role;
import com.flashopify.auth.domain.User;
import java.time.Instant;

/** Mirrors UserProfile in openapi.yaml. */
public record UserProfileResponse(
    long id, String email, String fullName, String phone, Role role, Instant createdAt) {

  public static UserProfileResponse from(User user) {
    return new UserProfileResponse(
        user.getId(),
        user.getEmail(),
        user.getFullName(),
        user.getPhone(),
        user.getRole(),
        user.getCreatedAt());
  }
}
