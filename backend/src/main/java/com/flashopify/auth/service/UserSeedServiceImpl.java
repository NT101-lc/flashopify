package com.flashopify.auth.service;

import com.flashopify.auth.domain.Address;
import com.flashopify.auth.domain.Role;
import com.flashopify.auth.domain.User;
import com.flashopify.auth.domain.UserStatus;
import com.flashopify.auth.dto.CustomerSeed;
import com.flashopify.auth.dto.CustomerSeedResult;
import com.flashopify.auth.dto.SeedUserResult;
import com.flashopify.auth.mapper.AddressMapper;
import com.flashopify.auth.mapper.UserMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent creation of seed accounts; see ADR-0011. */
@Service
@RequiredArgsConstructor
public class UserSeedServiceImpl implements UserSeedService {

  private final UserMapper userMapper;
  private final AddressMapper addressMapper;
  private final PasswordEncoder passwordEncoder;
  private final TokenService tokenService;
  private final Clock clock;

  @Override
  @Transactional
  public SeedUserResult ensureAdmin(String email, String rawPassword, String fullName) {
    String normalised = email.trim().toLowerCase(java.util.Locale.ROOT);
    User existing = userMapper.findByEmail(normalised);
    if (existing != null) {
      if (existing.getRole() != Role.ADMIN) {
        throw new IllegalStateException(
            "Seed admin email " + normalised + " already belongs to a non-admin user");
      }
      return new SeedUserResult(existing.getId(), false);
    }
    Instant now = clock.instant();
    User admin =
        User.builder()
            .email(normalised)
            .passwordHash(passwordEncoder.encode(rawPassword))
            .fullName(fullName)
            .role(Role.ADMIN)
            .status(UserStatus.ACTIVE)
            .createdAt(now)
            .updatedAt(now)
            .build();
    userMapper.insert(admin);
    return new SeedUserResult(admin.getId(), true);
  }

  @Override
  @Transactional
  public CustomerSeedResult ensureCustomers(List<CustomerSeed> seeds, String sharedPasswordHash) {
    if (seeds.isEmpty()) {
      return new CustomerSeedResult(Map.of(), 0, 0);
    }
    Map<String, Long> ids = new HashMap<>();
    userMapper
        .findIdsByEmails(seeds.stream().map(CustomerSeed::email).toList())
        .forEach(u -> ids.put(u.getEmail(), u.getId()));
    int skipped = ids.size();

    Instant now = clock.instant();
    List<CustomerSeed> missing = new ArrayList<>();
    List<User> users = new ArrayList<>();
    for (CustomerSeed seed : seeds) {
      if (!ids.containsKey(seed.email())) {
        missing.add(seed);
        users.add(
            User.builder()
                .email(seed.email())
                .passwordHash(sharedPasswordHash)
                .fullName(seed.fullName())
                .phone(seed.phone())
                .role(Role.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());
      }
    }
    if (!users.isEmpty()) {
      userMapper.insertBatch(users);
      List<Address> addresses = new ArrayList<>();
      for (int i = 0; i < users.size(); i++) {
        User user = users.get(i);
        CustomerSeed seed = missing.get(i);
        ids.put(user.getEmail(), user.getId());
        addresses.add(
            Address.builder()
                .userId(user.getId())
                .receiverName(seed.fullName())
                .phone(seed.phone())
                .addressLine(seed.address().addressLine())
                .ward(seed.address().ward())
                .district(seed.address().district())
                .city(seed.address().city())
                .defaultAddress(true)
                .createdAt(now)
                .build());
      }
      addressMapper.insertBatch(addresses);
    }
    return new CustomerSeedResult(ids, users.size(), skipped);
  }

  @Override
  public String hashPassword(String rawPassword) {
    return passwordEncoder.encode(rawPassword);
  }

  @Override
  @Transactional(readOnly = true)
  public String issueAccessToken(long userId) {
    User user = userMapper.findById(userId);
    if (user == null) {
      throw new IllegalStateException("Unknown user " + userId);
    }
    return tokenService.issue(user).accessToken();
  }
}
