package com.flashopify.auth.service;

import com.flashopify.auth.domain.Role;
import com.flashopify.auth.domain.User;
import com.flashopify.auth.domain.UserStatus;
import com.flashopify.auth.dto.LoginRequest;
import com.flashopify.auth.dto.RegisterRequest;
import com.flashopify.auth.dto.TokenResponse;
import com.flashopify.auth.dto.UserProfileResponse;
import com.flashopify.auth.mapper.UserMapper;
import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.security.LoginProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

  private final UserMapper userMapper;
  private final PasswordEncoder passwordEncoder;
  private final TokenService tokenService;
  private final Clock clock;
  private final LoginProperties loginProperties;

  /** Compared against when the email is unknown so response time does not reveal it. */
  private final String dummyHash;

  public AuthServiceImpl(
      UserMapper userMapper,
      PasswordEncoder passwordEncoder,
      TokenService tokenService,
      Clock clock,
      LoginProperties loginProperties) {
    this.userMapper = userMapper;
    this.passwordEncoder = passwordEncoder;
    this.tokenService = tokenService;
    this.clock = clock;
    this.loginProperties = loginProperties;
    this.dummyHash = passwordEncoder.encode("flashopify-dummy-password");
  }

  @Override
  public UserProfileResponse register(RegisterRequest request) {
    String email = normalise(request.email());
    if (userMapper.findByEmail(email) != null) {
      throw emailTaken();
    }
    Instant now = clock.instant();
    User user =
        User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(request.password()))
            .fullName(request.fullName().trim())
            .phone(request.phone())
            .role(Role.CUSTOMER)
            .status(UserStatus.ACTIVE)
            .createdAt(now)
            .updatedAt(now)
            .build();
    try {
      userMapper.insert(user);
    } catch (DuplicateKeyException e) {
      throw emailTaken();
    }
    return UserProfileResponse.from(user);
  }

  @Override
  public TokenResponse login(LoginRequest request) {
    User user = userMapper.findByEmail(normalise(request.email()));
    if (user == null || user.getStatus() == UserStatus.DELETED) {
      passwordEncoder.matches(request.password(), dummyHash);
      throw invalidCredentials();
    }
    Instant now = clock.instant();
    if (user.getStatus() == UserStatus.LOCKED || isLocked(user, now)) {
      throw accountLocked(user);
    }
    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      userMapper.recordFailedLogin(
          user.getId(),
          now.plus(loginProperties.lockDuration()),
          loginProperties.maxFailedAttempts());
      User fresh = userMapper.findById(user.getId());
      if (fresh != null && isLocked(fresh, now)) {
        throw accountLocked(fresh);
      }
      throw invalidCredentials();
    }
    if (user.getFailedLoginCount() > 0 || user.getLockedUntil() != null) {
      userMapper.resetLoginFailures(user.getId());
    }
    TokenPair tokens = tokenService.issue(user);
    return new TokenResponse(
        tokens.accessToken(),
        tokens.refreshToken(),
        TokenResponse.BEARER,
        tokens.expiresIn(),
        UserProfileResponse.from(user));
  }

  private static String normalise(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean isLocked(User user, Instant now) {
    return user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
  }

  private static BusinessException emailTaken() {
    return new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email is already registered");
  }

  private static BusinessException invalidCredentials() {
    return new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Wrong email or password");
  }

  private static BusinessException accountLocked(User user) {
    String until = user.getLockedUntil() == null ? "" : " until " + user.getLockedUntil();
    return new BusinessException(ErrorCode.ACCOUNT_LOCKED, "Account is locked" + until);
  }
}
