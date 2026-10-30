package com.flashopify.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashopify.auth.domain.Address;
import com.flashopify.auth.domain.Role;
import com.flashopify.auth.domain.User;
import com.flashopify.auth.domain.UserStatus;
import com.flashopify.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** TC-4: batch inserts and the large IN lookup. */
class SeedBatchMapperIT extends AbstractIntegrationTest {

  @Autowired UserMapper userMapper;
  @Autowired AddressMapper addressMapper;
  @Autowired JdbcTemplate jdbc;

  private List<User> users(String prefix, int n) {
    Instant now = Instant.now();
    List<User> list = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      list.add(
          User.builder()
              .email(prefix + i + "@batch.local")
              .passwordHash("$2a$10$hash")
              .fullName("Batch " + i)
              .phone("09" + i)
              .role(Role.CUSTOMER)
              .status(UserStatus.ACTIVE)
              .createdAt(now)
              .updatedAt(now)
              .build());
    }
    return list;
  }

  @Test
  void insertBatchFillsGeneratedKeysInOrder() {
    List<User> users = users("b" + UUID.randomUUID() + "-", 5);

    userMapper.insertBatch(users);

    assertThat(users).allSatisfy(u -> assertThat(u.getId()).isNotNull());
    assertThat(users.stream().map(User::getId).distinct()).hasSize(5);
    for (User u : users) {
      assertThat(userMapper.findById(u.getId()).getEmail()).isEqualTo(u.getEmail());
    }
  }

  @Test
  void addressInsertBatchWritesDefaultAddresses() {
    List<User> users = users("a" + UUID.randomUUID() + "-", 3);
    userMapper.insertBatch(users);
    Instant now = Instant.now();
    List<Address> addresses =
        users.stream()
            .map(
                u ->
                    Address.builder()
                        .userId(u.getId())
                        .receiverName("R")
                        .phone("0900000000")
                        .addressLine("1 Street")
                        .ward("W")
                        .district("D")
                        .city("C")
                        .defaultAddress(true)
                        .createdAt(now)
                        .build())
            .toList();

    addressMapper.insertBatch(addresses);

    for (User u : users) {
      assertThat(addressMapper.countByUser(u.getId())).isEqualTo(1);
      assertThat(addressMapper.findAllByUser(u.getId()).get(0).isDefaultAddress()).isTrue();
    }
  }

  @Test
  void findIdsByEmailsHandlesTwoHundredEmails() {
    String prefix = "f" + UUID.randomUUID() + "-";
    List<User> users = users(prefix, 120);
    userMapper.insertBatch(users);
    List<String> emails = new ArrayList<>(users.stream().map(User::getEmail).toList());
    for (int i = 0; i < 80; i++) {
      emails.add(prefix + "missing" + i + "@batch.local");
    }
    assertThat(emails).hasSize(200);

    List<User> found = userMapper.findIdsByEmails(emails);

    assertThat(found).hasSize(120);
    assertThat(found.stream().map(User::getId).toList())
        .containsExactlyInAnyOrderElementsOf(users.stream().map(User::getId).toList());
  }
}
