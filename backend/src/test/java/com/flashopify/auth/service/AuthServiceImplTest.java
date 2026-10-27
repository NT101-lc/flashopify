package com.flashopify.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-10-27T08:00:00Z");

  @Mock UserMapper userMapper;
  @Mock PasswordEncoder passwordEncoder;
  @Mock TokenService tokenService;

  AuthServiceImpl service;

  @BeforeEach
  void setUp() {
    when(passwordEncoder.encode(anyString())).thenReturn("hash");
    when(userMapper.insert(any()))
        .thenAnswer(
            inv -> {
              inv.<User>getArgument(0).setId(99L);
              return 1;
            });
    service =
        new AuthServiceImpl(
            userMapper,
            passwordEncoder,
            tokenService,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new LoginProperties(5, Duration.ofMinutes(15)));
  }

  private static User user() {
    return User.builder()
        .id(7L)
        .email("an@example.com")
        .passwordHash("stored-hash")
        .fullName("An")
        .role(Role.CUSTOMER)
        .status(UserStatus.ACTIVE)
        .createdAt(NOW)
        .build();
  }

  private static RegisterRequest registerRequest() {
    return new RegisterRequest("  An@Example.COM ", "S3cure!pass", " An ", "0901234567");
  }

  @Test
  void registerStoresHashedPasswordCustomerRoleAndLowercasedEmail() {
    UserProfileResponse profile = service.register(registerRequest());

    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userMapper).insert(captor.capture());
    User saved = captor.getValue();
    assertThat(saved.getEmail()).isEqualTo("an@example.com");
    assertThat(saved.getPasswordHash()).isEqualTo("hash");
    assertThat(saved.getRole()).isEqualTo(Role.CUSTOMER);
    assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(saved.getFullName()).isEqualTo("An");
    assertThat(profile.email()).isEqualTo("an@example.com");
    assertThat(profile.role()).isEqualTo(Role.CUSTOMER);
  }

  @Test
  void registerRejectsExistingEmail() {
    when(userMapper.findByEmail("an@example.com")).thenReturn(user());

    assertThatThrownBy(() -> service.register(registerRequest()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));
    verify(userMapper, never()).insert(any());
  }

  @Test
  void registerMapsDuplicateKeyRaceToEmailAlreadyExists() {
    doThrow(new DuplicateKeyException("dup")).when(userMapper).insert(any());

    assertThatThrownBy(() -> service.register(registerRequest()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));
  }

  @Test
  void loginUnknownEmailIs401AndStillRunsPasswordEncoder() {
    assertThatThrownBy(() -> service.login(new LoginRequest("x@example.com", "whatever1")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    verify(passwordEncoder).matches(eq("whatever1"), anyString());
    verify(userMapper, never()).recordFailedLogin(anyLong(), any(), anyInt());
  }

  @Test
  void loginDeletedUserIs401() {
    User deleted = user();
    deleted.setStatus(UserStatus.DELETED);
    when(userMapper.findByEmail("an@example.com")).thenReturn(deleted);

    assertThatThrownBy(() -> service.login(new LoginRequest("an@example.com", "pw123456")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
  }

  @Test
  void loginWrongPasswordIs401AndRecordsFailure() {
    User u = user();
    when(userMapper.findByEmail("an@example.com")).thenReturn(u);
    when(userMapper.findById(7L)).thenReturn(u);

    assertThatThrownBy(() -> service.login(new LoginRequest("an@example.com", "wrong-pass")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    verify(userMapper).recordFailedLogin(7L, NOW.plus(Duration.ofMinutes(15)), 5);
  }

  @Test
  void loginFailureThatTriggersLockIs423() {
    User u = user();
    User locked = user();
    locked.setLockedUntil(NOW.plus(Duration.ofMinutes(15)));
    when(userMapper.findByEmail("an@example.com")).thenReturn(u);
    when(userMapper.findById(7L)).thenReturn(locked);

    assertThatThrownBy(() -> service.login(new LoginRequest("an@example.com", "wrong-pass")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED));
  }

  @Test
  void loginWhileLockedIs423WithoutCheckingPassword() {
    User u = user();
    u.setLockedUntil(NOW.plusSeconds(60));
    when(userMapper.findByEmail("an@example.com")).thenReturn(u);
    when(passwordEncoder.matches(anyString(), eq("stored-hash"))).thenReturn(true);

    assertThatThrownBy(() -> service.login(new LoginRequest("an@example.com", "right-pass")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED));
    verify(passwordEncoder, never()).matches(anyString(), eq("stored-hash"));
  }

  @Test
  void loginAdminLockedStatusIs423() {
    User u = user();
    u.setStatus(UserStatus.LOCKED);
    when(userMapper.findByEmail("an@example.com")).thenReturn(u);

    assertThatThrownBy(() -> service.login(new LoginRequest("an@example.com", "right-pass")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED));
  }

  @Test
  void loginAfterLockExpiredSucceedsAndResetsCounters() {
    User u = user();
    u.setLockedUntil(NOW.minusSeconds(1));
    when(userMapper.findByEmail("an@example.com")).thenReturn(u);
    when(passwordEncoder.matches("right-pass", "stored-hash")).thenReturn(true);
    when(tokenService.issue(u)).thenReturn(new TokenPair("access", "refresh", 900));

    TokenResponse response = service.login(new LoginRequest("an@example.com", "right-pass"));

    verify(userMapper).resetLoginFailures(7L);
    assertThat(response.accessToken()).isEqualTo("access");
  }

  @Test
  void loginSuccessReturnsTokensAndSkipsResetWhenNothingToReset() {
    User u = user();
    when(userMapper.findByEmail("an@example.com")).thenReturn(u);
    when(passwordEncoder.matches("right-pass", "stored-hash")).thenReturn(true);
    when(tokenService.issue(u)).thenReturn(new TokenPair("access", "refresh", 900));

    TokenResponse response = service.login(new LoginRequest(" AN@example.com", "right-pass"));

    assertThat(response.tokenType()).isEqualTo("Bearer");
    assertThat(response.expiresIn()).isEqualTo(900);
    assertThat(response.refreshToken()).isEqualTo("refresh");
    assertThat(response.user().id()).isEqualTo(7L);
    verify(userMapper, never()).resetLoginFailures(anyLong());
  }
}
