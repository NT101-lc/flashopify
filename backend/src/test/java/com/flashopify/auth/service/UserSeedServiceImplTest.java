package com.flashopify.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashopify.auth.domain.Address;
import com.flashopify.auth.domain.Role;
import com.flashopify.auth.domain.User;
import com.flashopify.auth.dto.CustomerSeed;
import com.flashopify.auth.dto.CustomerSeedResult;
import com.flashopify.auth.dto.SeedUserResult;
import com.flashopify.auth.mapper.AddressMapper;
import com.flashopify.auth.mapper.UserMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/** TC-1. */
class UserSeedServiceImplTest {

  private UserMapper userMapper;
  private AddressMapper addressMapper;
  private PasswordEncoder encoder;
  private TokenService tokens;
  private UserSeedServiceImpl service;

  @BeforeEach
  void setUp() {
    userMapper = mock(UserMapper.class);
    addressMapper = mock(AddressMapper.class);
    encoder = mock(PasswordEncoder.class);
    tokens = mock(TokenService.class);
    Clock clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);
    service = new UserSeedServiceImpl(userMapper, addressMapper, encoder, tokens, clock);
  }

  private static CustomerSeed seed(String email) {
    return new CustomerSeed(
        email, "Name " + email, "0900000001", new CustomerSeed.AddressSeed("1 A", "W", "D", "C"));
  }

  @Test
  void existingAdminIsSkippedWithoutEncoding() {
    when(userMapper.findByEmail("admin@x.local"))
        .thenReturn(User.builder().id(3L).role(Role.ADMIN).build());

    SeedUserResult result = service.ensureAdmin("Admin@X.local", "pw", "Admin");

    assertThat(result).isEqualTo(new SeedUserResult(3L, false));
    verifyNoInteractions(encoder);
    verify(userMapper, never()).insert(any());
  }

  @Test
  void existingCustomerWithTheAdminEmailIsRejected() {
    when(userMapper.findByEmail("admin@x.local"))
        .thenReturn(User.builder().id(3L).role(Role.CUSTOMER).build());

    assertThatThrownBy(() -> service.ensureAdmin("admin@x.local", "pw", "Admin"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-admin");
    verify(userMapper, never()).insert(any());
  }

  @Test
  void missingAdminIsCreatedWithAHashedPassword() {
    when(userMapper.findByEmail("admin@x.local")).thenReturn(null);
    when(encoder.encode("pw")).thenReturn("HASH");
    doAnswer(
            i -> {
              ((User) i.getArgument(0)).setId(9L);
              return 1;
            })
        .when(userMapper)
        .insert(any());

    SeedUserResult result = service.ensureAdmin("admin@x.local", "pw", "Admin");

    assertThat(result).isEqualTo(new SeedUserResult(9L, true));
    ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
    verify(userMapper).insert(user.capture());
    assertThat(user.getValue().getRole()).isEqualTo(Role.ADMIN);
    assertThat(user.getValue().getPasswordHash()).isEqualTo("HASH");
  }

  @Test
  void customersInsertOnlyTheMissingOnesAndReturnIdsForAll() {
    when(userMapper.findIdsByEmails(List.of("a@x", "b@x", "c@x")))
        .thenReturn(List.of(User.builder().id(1L).email("b@x").build()));
    doAnswer(
            i -> {
              List<User> users = i.getArgument(0);
              long next = 100;
              for (User u : users) {
                u.setId(next++);
              }
              return users.size();
            })
        .when(userMapper)
        .insertBatch(any());

    CustomerSeedResult result =
        service.ensureCustomers(List.of(seed("a@x"), seed("b@x"), seed("c@x")), "SHARED");

    assertThat(result.created()).isEqualTo(2);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.userIds()).containsEntry("a@x", 100L).containsEntry("b@x", 1L);
    assertThat(result.userIds()).containsEntry("c@x", 101L).hasSize(3);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<User>> users = ArgumentCaptor.forClass(List.class);
    verify(userMapper).insertBatch(users.capture());
    assertThat(users.getValue())
        .extracting(User::getEmail, User::getPasswordHash, User::getRole)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("a@x", "SHARED", Role.CUSTOMER),
            org.assertj.core.groups.Tuple.tuple("c@x", "SHARED", Role.CUSTOMER));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Address>> addresses = ArgumentCaptor.forClass(List.class);
    verify(addressMapper).insertBatch(addresses.capture());
    assertThat(addresses.getValue())
        .extracting(Address::getUserId, Address::isDefaultAddress)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(100L, true),
            org.assertj.core.groups.Tuple.tuple(101L, true));
    verify(encoder, never()).encode(anyString());
  }

  @Test
  void nothingMissingMeansNoInserts() {
    when(userMapper.findIdsByEmails(List.of("a@x")))
        .thenReturn(List.of(User.builder().id(1L).email("a@x").build()));

    CustomerSeedResult result = service.ensureCustomers(List.of(seed("a@x")), "SHARED");

    assertThat(result.created()).isZero();
    assertThat(result.skipped()).isEqualTo(1);
    verify(userMapper, never()).insertBatch(any());
    verifyNoInteractions(addressMapper);
  }

  @Test
  void emptyListDoesNothing() {
    assertThat(service.ensureCustomers(List.of(), "SHARED").userIds()).isEmpty();
    verifyNoInteractions(userMapper, addressMapper);
  }

  @Test
  void hashPasswordUsesTheEncoder() {
    when(encoder.encode("raw")).thenReturn("H");

    assertThat(service.hashPassword("raw")).isEqualTo("H");
  }

  @Test
  void issueAccessTokenSignsForTheUser() {
    User user = User.builder().id(4L).role(Role.CUSTOMER).build();
    when(userMapper.findById(4L)).thenReturn(user);
    when(tokens.issue(user)).thenReturn(new TokenPair("ACCESS", "REFRESH", 900));

    assertThat(service.issueAccessToken(4L)).isEqualTo("ACCESS");
    assertThatThrownBy(() -> service.issueAccessToken(5L))
        .isInstanceOf(IllegalStateException.class);
  }
}
