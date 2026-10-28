package com.flashopify.auth.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Row of the users table. Owned by the auth module. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

  private Long id;
  private String email;
  private String passwordHash;
  private String fullName;
  private String phone;
  private Role role;
  private UserStatus status;
  private int failedLoginCount;
  private Instant lockedUntil;
  private Instant createdAt;
  private Instant updatedAt;
}
