package com.flashopify.admin.seed;

import com.flashopify.auth.dto.CustomerSeed;
import com.flashopify.auth.dto.CustomerSeedResult;
import com.flashopify.auth.dto.SeedUserResult;
import com.flashopify.auth.service.UserSeedService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;

/**
 * Seeds the admin and the synthetic customers through {@link UserSeedService} only, then exports
 * the customers' credentials for k6. All customers share one password hashed once: BCrypt at cost
 * 10 takes about 50 ms, so 1,000 separate hashes would cost a minute for no benefit (ADR-0011).
 */
@Slf4j
@Order(20)
public class UserSeeder implements SeedStep {

  private static final List<String> FAMILY = List.of("Nguyen", "Tran", "Le", "Pham", "Hoang", "Vu");
  private static final List<String> MIDDLE = List.of("Van", "Thi", "Minh", "Quoc", "Ngoc");
  private static final List<String> GIVEN =
      List.of("An", "Binh", "Chi", "Dung", "Giang", "Hanh", "Khoa", "Linh", "Nam", "Phuc");
  private static final List<String> STREETS =
      List.of("Le Loi", "Nguyen Hue", "Tran Hung Dao", "Hai Ba Trung", "Pham Ngu Lao");

  /** city, district, ward */
  private static final List<List<String>> PLACES =
      List.of(
          List.of("Ho Chi Minh", "Quan 1", "Phuong Ben Nghe"),
          List.of("Ha Noi", "Hoan Kiem", "Phuong Hang Bac"),
          List.of("Da Nang", "Hai Chau", "Phuong Thach Thang"),
          List.of("Can Tho", "Ninh Kieu", "Phuong Tan An"),
          List.of("Hai Phong", "Ngo Quyen", "Phuong May To"),
          List.of("Hue", "Phu Hoi", "Phuong Vinh Ninh"));

  private final UserSeedService users;
  private final SeedProperties.Users props;

  public UserSeeder(UserSeedService users, SeedProperties seedProps) {
    this.users = users;
    this.props = seedProps.users();
  }

  @Override
  public String name() {
    return "users";
  }

  @Override
  public boolean enabled() {
    return props.enabled();
  }

  @Override
  public UserSeedReport run() {
    long started = System.nanoTime();
    require(props.adminEmail(), "SEED_ADMIN_EMAIL (app.seed.users.admin-email)");
    require(props.adminPassword(), "SEED_ADMIN_PASSWORD (app.seed.users.admin-password)");
    require(props.customerPassword(), "SEED_CUSTOMER_PASSWORD (app.seed.users.customer-password)");

    SeedUserResult admin =
        users.ensureAdmin(props.adminEmail(), props.adminPassword(), props.adminName());
    log.info("seed admin {}", admin.created() ? "created" : "skipped");

    String sharedHash = users.hashPassword(props.customerPassword());
    List<CustomerSeed> all = customers(props);
    Map<String, Long> ids = new HashMap<>();
    int created = 0;
    int skipped = 0;
    int chunk = Math.max(1, props.chunkSize());
    for (int from = 0; from < all.size(); from += chunk) {
      List<CustomerSeed> part = all.subList(from, Math.min(all.size(), from + chunk));
      CustomerSeedResult result = users.ensureCustomers(part, sharedHash);
      ids.putAll(result.userIds());
      created += result.created();
      skipped += result.skipped();
    }
    log.info("seed customers created={} skipped={}", created, skipped);

    List<UsersCsvWriter.Row> rows = new ArrayList<>();
    for (CustomerSeed seed : all) {
      long id = ids.get(seed.email());
      String token = props.exportTokens() ? users.issueAccessToken(id) : null;
      rows.add(new UsersCsvWriter.Row(id, seed.email(), props.customerPassword(), token));
    }
    Path path = Path.of(props.exportPath());
    try {
      UsersCsvWriter.write(path, rows, props.exportTokens());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write " + path.toAbsolutePath().normalize(), e);
    }
    log.info("seed users exported {} rows to {}", rows.size(), path.toAbsolutePath().normalize());
    return new UserSeedReport(
        admin.created(), created, skipped, rows.size(), (System.nanoTime() - started) / 1_000_000);
  }

  /** Deterministic: the same index always yields the same customer. */
  static List<CustomerSeed> customers(SeedProperties.Users props) {
    List<CustomerSeed> list = new ArrayList<>();
    for (int i = 1; i <= props.customerCount(); i++) {
      List<String> place = PLACES.get(i % PLACES.size());
      String fullName =
          FAMILY.get(i % FAMILY.size())
              + " "
              + MIDDLE.get((i / 2) % MIDDLE.size())
              + " "
              + GIVEN.get(i % GIVEN.size());
      String address = (i % 200 + 1) + " " + STREETS.get(i % STREETS.size());
      list.add(
          new CustomerSeed(
              String.format(props.emailPattern(), i),
              fullName,
              String.format("09%08d", i),
              new CustomerSeed.AddressSeed(address, place.get(2), place.get(1), place.get(0))));
    }
    return list;
  }

  private static void require(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing "
              + what
              + ": set it in infra/docker/.env (see .env.example) before seeding users");
    }
  }
}
