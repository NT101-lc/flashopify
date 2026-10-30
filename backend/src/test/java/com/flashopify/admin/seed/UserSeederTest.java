package com.flashopify.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashopify.auth.dto.CustomerSeed;
import com.flashopify.auth.dto.CustomerSeedResult;
import com.flashopify.auth.dto.SeedUserResult;
import com.flashopify.auth.service.UserSeedService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TC-2. */
class UserSeederTest {

  @TempDir Path dir;
  private UserSeedService service;

  @BeforeEach
  void setUp() {
    service = mock(UserSeedService.class);
    when(service.hashPassword(anyString())).thenReturn("HASH");
    when(service.ensureAdmin(anyString(), anyString(), anyString()))
        .thenReturn(new SeedUserResult(1, true));
    when(service.ensureCustomers(any(), anyString()))
        .thenAnswer(
            i -> {
              List<CustomerSeed> seeds = i.getArgument(0);
              Map<String, Long> ids = new HashMap<>();
              for (CustomerSeed s : seeds) {
                ids.put(s.email(), (long) s.email().hashCode() & 0xffff);
              }
              return new CustomerSeedResult(ids, seeds.size(), 0);
            });
    when(service.issueAccessToken(anyLong())).thenAnswer(i -> "tok" + i.getArgument(0));
  }

  private UserSeeder seeder(
      String adminEmail, String adminPassword, String customerPassword, int count, boolean tokens) {
    SeedProperties.Users users =
        new SeedProperties.Users(
            true,
            adminEmail,
            adminPassword,
            "Admin",
            count,
            2,
            "customer%04d@seed.flashopify.local",
            customerPassword,
            dir.resolve("out/users.csv").toString(),
            tokens);
    return new UserSeeder(
        service,
        new SeedProperties(
            true,
            new SeedProperties.Products(true),
            users,
            new SeedProperties.Dummyjson("x", 1),
            new SeedProperties.Image(0, java.time.Duration.ZERO, 1, java.time.Duration.ZERO)));
  }

  @Test
  void missingAdminPasswordFailsFastWithAClearMessage() {
    UserSeeder seeder = seeder("admin@x.local", "", "cust", 3, false);

    assertThatThrownBy(seeder::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SEED_ADMIN_PASSWORD");
    verifyNoInteractions(service);
  }

  @Test
  void missingAdminEmailAndCustomerPasswordAlsoFail() {
    assertThatThrownBy(() -> seeder("", "pw", "cust", 3, false).run())
        .hasMessageContaining("SEED_ADMIN_EMAIL");
    assertThatThrownBy(() -> seeder("a@x", "pw", " ", 3, false).run())
        .hasMessageContaining("SEED_CUSTOMER_PASSWORD");
  }

  @Test
  void seedsInChunksHashesOnceAndExportsEveryCustomer() throws IOException {
    UserSeedReport report = seeder("admin@x.local", "pw", "cust", 5, false).run();

    assertThat(report.adminCreated()).isTrue();
    assertThat(report.created()).isEqualTo(5);
    assertThat(report.exported()).isEqualTo(5);
    verify(service).hashPassword("cust");
    verify(service, org.mockito.Mockito.times(3))
        .ensureCustomers(any(), org.mockito.ArgumentMatchers.eq("HASH"));
    verify(service, never()).issueAccessToken(anyLong());
    List<String> lines = Files.readAllLines(dir.resolve("out/users.csv"));
    assertThat(lines).hasSize(6);
    assertThat(lines.get(0)).isEqualTo("userId,email,password");
    assertThat(lines.get(1)).endsWith(",customer0001@seed.flashopify.local,cust");
    assertThat(dir.resolve("out/users.csv.tmp")).doesNotExist();
  }

  @Test
  void exportTokensAddsAColumn() throws IOException {
    seeder("admin@x.local", "pw", "cust", 2, true).run();

    List<String> lines = Files.readAllLines(dir.resolve("out/users.csv"));
    assertThat(lines.get(0)).isEqualTo("userId,email,password,accessToken");
    assertThat(lines.get(1)).matches("\\d+,customer0001@seed\\.flashopify\\.local,cust,tok\\d+");
  }

  @Test
  void generatedCustomersAreDeterministicAndUnique() {
    SeedProperties.Users props =
        new SeedProperties.Users(
            true, "a", "b", "n", 300, 200, "customer%04d@seed.flashopify.local", "c", "x", false);

    List<CustomerSeed> first = UserSeeder.customers(props);
    List<CustomerSeed> second = UserSeeder.customers(props);

    assertThat(first).isEqualTo(second).hasSize(300);
    assertThat(first.stream().map(CustomerSeed::email).distinct()).hasSize(300);
    assertThat(first.stream().map(CustomerSeed::phone).distinct()).hasSize(300);
    assertThat(first.get(0).email()).isEqualTo("customer0001@seed.flashopify.local");
    assertThat(first.get(0).phone()).isEqualTo("0900000001");
    assertThat(first.get(0).address().city()).isNotBlank();
  }

  @Test
  void csvEscapesCommasQuotesAndNewlines() throws IOException {
    Path file = dir.resolve("e.csv");

    UsersCsvWriter.write(
        file, List.of(new UsersCsvWriter.Row(7, "a@x", "pa,ss\"w\nd", null)), false);

    assertThat(Files.readString(file))
        .isEqualTo("userId,email,password\n7,a@x,\"pa,ss\"\"w\nd\"\n");
    assertThat(UsersCsvWriter.escape(null)).isEmpty();
    assertThat(UsersCsvWriter.escape("plain")).isEqualTo("plain");
  }
}
