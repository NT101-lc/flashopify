package com.flashopify.auth.service;

import com.flashopify.auth.dto.CustomerSeed;
import com.flashopify.auth.dto.CustomerSeedResult;
import com.flashopify.auth.dto.SeedUserResult;
import java.util.List;

/** Bulk-creation entry point of the auth module, used by the seed job in {@code admin.seed}. */
public interface UserSeedService {

  /**
   * Creates the ADMIN account if it does not exist. An existing ADMIN is left untouched (its
   * password is not overwritten).
   *
   * @throws IllegalStateException when the email belongs to a non-admin user
   */
  SeedUserResult ensureAdmin(String email, String rawPassword, String fullName);

  /**
   * Creates the customers that do not exist yet, each with one default address, in one transaction.
   * No hashing happens here: every new customer gets {@code sharedPasswordHash}.
   */
  CustomerSeedResult ensureCustomers(List<CustomerSeed> seeds, String sharedPasswordHash);

  /** BCrypt hash (the application's encoder, cost 10) of a raw password. */
  String hashPassword(String rawPassword);

  /** Signs an access token for the user (used only to export load-test credentials). */
  String issueAccessToken(long userId);
}
