package com.flashopify.auth.mapper;

import com.flashopify.auth.domain.User;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for the users table; module-private to auth. */
@Mapper
public interface UserMapper {

  User findByEmail(@Param("email") String email);

  User findById(@Param("id") long id);

  /** Fills user.id from the generated key. */
  int insert(User user);

  /**
   * Atomically counts a failed login. The maxAttempts-th failure sets locked_until and resets the
   * counter; callers re-read the user to see whether the account is now locked.
   */
  int recordFailedLogin(
      @Param("id") long id,
      @Param("lockUntil") Instant lockUntil,
      @Param("maxAttempts") int maxAttempts);

  int resetLoginFailures(@Param("id") long id);

  int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

  /** Row lock (SELECT ... FOR UPDATE); serialises per-user writes such as address changes. */
  User lockForUpdate(@Param("id") long id);

  /** Updates only the non-null arguments; callers must pass at least one. */
  int updateProfile(
      @Param("id") long id, @Param("fullName") String fullName, @Param("phone") String phone);

  /** Soft delete: status DELETED, personal data replaced, credentials made unusable. */
  int anonymize(
      @Param("id") long id,
      @Param("email") String email,
      @Param("fullName") String fullName,
      @Param("passwordHash") String passwordHash);
}
