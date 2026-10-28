package com.flashopify.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashopify.auth.domain.Role;
import com.flashopify.auth.domain.User;
import com.flashopify.auth.domain.UserDeletedEvent;
import com.flashopify.auth.domain.UserStatus;
import com.flashopify.auth.dto.UpdateProfileRequest;
import com.flashopify.auth.dto.UserProfileResponse;
import com.flashopify.auth.mapper.AddressMapper;
import com.flashopify.auth.mapper.UserMapper;
import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import java.time.Clock;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-10-27T08:00:00Z");
  private static final long ID = 7L;

  @Mock UserMapper userMapper;
  @Mock AddressMapper addressMapper;
  @Mock PasswordEncoder passwordEncoder;
  @Mock ApplicationEventPublisher events;

  UserServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new UserServiceImpl(
            userMapper, addressMapper, passwordEncoder, events, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static User user(UserStatus status) {
    return User.builder()
        .id(ID)
        .email("an@example.com")
        .passwordHash("stored")
        .fullName("An")
        .phone("0901234567")
        .role(Role.CUSTOMER)
        .status(status)
        .createdAt(NOW)
        .build();
  }

  private static void assertCode(Throwable t, ErrorCode code) {
    assertThat(t)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }

  @Test
  void getProfileReturnsActiveUser() {
    when(userMapper.findById(ID)).thenReturn(user(UserStatus.ACTIVE));

    assertThat(service.getProfile(ID).email()).isEqualTo("an@example.com");
  }

  @Test
  void getProfileOfDeletedOrMissingUserIs401() {
    when(userMapper.findById(ID)).thenReturn(user(UserStatus.DELETED));
    assertThatThrownBy(() -> service.getProfile(ID))
        .satisfies(t -> assertCode(t, ErrorCode.UNAUTHORIZED));

    when(userMapper.findById(ID)).thenReturn(null);
    assertThatThrownBy(() -> service.getProfile(ID))
        .satisfies(t -> assertCode(t, ErrorCode.UNAUTHORIZED));
  }

  @Test
  void findActiveByIdIsEmptyForDeletedAndMissing() {
    when(userMapper.findById(ID)).thenReturn(user(UserStatus.DELETED));
    assertThat(service.findActiveById(ID)).isEmpty();

    when(userMapper.findById(ID)).thenReturn(null);
    assertThat(service.findActiveById(ID)).isEmpty();

    when(userMapper.findById(ID)).thenReturn(user(UserStatus.ACTIVE));
    assertThat(service.findActiveById(ID)).map(UserProfileResponse::id).contains(ID);
  }

  @Test
  void updateProfileTrimsNameAndPersistsOnlyGivenFields() {
    when(userMapper.findById(ID)).thenReturn(user(UserStatus.ACTIVE));

    service.updateProfile(ID, new UpdateProfileRequest("  New Name ", null));

    verify(userMapper).updateProfile(ID, "New Name", null);
  }

  @Test
  void updateProfileWithNoFieldsSkipsUpdate() {
    when(userMapper.findById(ID)).thenReturn(user(UserStatus.ACTIVE));

    service.updateProfile(ID, new UpdateProfileRequest(null, null));

    verify(userMapper, never()).updateProfile(anyLong(), any(), any());
  }

  @Test
  void updateProfileBlankNameIsValidationError() {
    assertThatThrownBy(() -> service.updateProfile(ID, new UpdateProfileRequest("   ", null)))
        .satisfies(t -> assertCode(t, ErrorCode.VALIDATION_ERROR));
  }

  @Test
  void updateProfileOfDeletedUserIs401() {
    when(userMapper.findById(ID)).thenReturn(user(UserStatus.DELETED));

    assertThatThrownBy(() -> service.updateProfile(ID, new UpdateProfileRequest("X", null)))
        .satisfies(t -> assertCode(t, ErrorCode.UNAUTHORIZED));
    verify(userMapper, never()).updateProfile(anyLong(), any(), any());
  }

  @Test
  void deleteAccountWithWrongPasswordChangesNothing() {
    when(userMapper.lockForUpdate(ID)).thenReturn(user(UserStatus.ACTIVE));
    when(passwordEncoder.matches("bad", "stored")).thenReturn(false);

    assertThatThrownBy(() -> service.deleteAccount(ID, "bad"))
        .satisfies(t -> assertCode(t, ErrorCode.INVALID_CREDENTIALS));
    verify(addressMapper, never()).deleteAllByUser(anyLong());
    verify(userMapper, never()).anonymize(anyLong(), anyString(), anyString(), anyString());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void deleteAccountAnonymisesRemovesAddressesAndPublishesEvent() {
    when(userMapper.lockForUpdate(ID)).thenReturn(user(UserStatus.ACTIVE));
    when(passwordEncoder.matches("good", "stored")).thenReturn(true);
    when(passwordEncoder.encode(anyString())).thenReturn("random-hash");

    service.deleteAccount(ID, "good");

    verify(addressMapper).deleteAllByUser(ID);
    verify(userMapper)
        .anonymize(eq(ID), eq("deleted-7@deleted.invalid"), eq("Deleted user"), eq("random-hash"));
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(captor.capture());
    assertThat(captor.getValue()).isEqualTo(new UserDeletedEvent(ID, NOW));
  }

  @Test
  void deleteAccountOfAlreadyDeletedUserIs401() {
    when(userMapper.lockForUpdate(ID)).thenReturn(user(UserStatus.DELETED));

    assertThatThrownBy(() -> service.deleteAccount(ID, "x"))
        .satisfies(t -> assertCode(t, ErrorCode.UNAUTHORIZED));
  }
}
