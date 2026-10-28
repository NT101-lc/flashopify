package com.flashopify.auth.service;

import com.flashopify.auth.dto.UpdateProfileRequest;
import com.flashopify.auth.dto.UserProfileResponse;
import java.util.Optional;

/** Public entry point to user data for the profile API and for other modules. */
public interface UserService {

  /** Profile of an existing, non-deleted user; otherwise 401 UNAUTHORIZED. */
  UserProfileResponse getProfile(long userId);

  /** Same as {@link #getProfile} but empty instead of an error for missing/deleted users. */
  Optional<UserProfileResponse> findActiveById(long userId);

  UserProfileResponse updateProfile(long userId, UpdateProfileRequest request);

  /**
   * Soft-deletes the account after a password check: anonymises personal data, removes addresses
   * and publishes a UserDeletedEvent. Orders and payments are untouched.
   */
  void deleteAccount(long userId, String password);
}
