package com.flashopify.auth.domain;

/** Account status; mirrors the users.status column. */
public enum UserStatus {
  ACTIVE,
  LOCKED,
  DELETED
}
