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
}
