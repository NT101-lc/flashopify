package com.flashopify.auth.service;

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
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

  private final UserMapper userMapper;
  private final AddressMapper addressMapper;
  private final PasswordEncoder passwordEncoder;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  @Override
  public UserProfileResponse getProfile(long userId) {
    return UserProfileResponse.from(requireActive(userMapper.findById(userId)));
  }

  @Override
  public Optional<UserProfileResponse> findActiveById(long userId) {
    User user = userMapper.findById(userId);
    if (user == null || user.getStatus() == UserStatus.DELETED) {
      return Optional.empty();
    }
    return Optional.of(UserProfileResponse.from(user));
  }

  @Override
  @Transactional
  public UserProfileResponse updateProfile(long userId, UpdateProfileRequest request) {
    String fullName = request.fullName() == null ? null : request.fullName().trim();
    if (fullName != null && fullName.isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "fullName must not be blank");
    }
    requireActive(userMapper.findById(userId));
    if (fullName != null || request.phone() != null) {
      userMapper.updateProfile(userId, fullName, request.phone());
    }
    return UserProfileResponse.from(requireActive(userMapper.findById(userId)));
  }

  @Override
  @Transactional
  public void deleteAccount(long userId, String password) {
    User user = requireActive(userMapper.lockForUpdate(userId));
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Password is wrong");
    }
    addressMapper.deleteAllByUser(userId);
    userMapper.anonymize(
        userId,
        "deleted-" + userId + "@deleted.invalid",
        "Deleted user",
        passwordEncoder.encode(UUID.randomUUID().toString()));
    events.publishEvent(new UserDeletedEvent(userId, clock.instant()));
  }

  private static User requireActive(User user) {
    if (user == null || user.getStatus() == UserStatus.DELETED) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
    }
    return user;
  }
}
