package com.flashopify.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.common.security.JwtConfig;
import com.flashopify.support.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TC-3: user seed against a real MySQL and Redis. Emails carry a per-run random prefix because the
 * containers are shared with other tests.
 */
@ActiveProfiles("seed")
@TestPropertySource(
    properties = {
      "spring.main.web-application-type=servlet",
      "app.seed.auto-run=false",
      "app.seed.users.customer-count=50",
      "app.seed.users.chunk-size=20",
      "app.seed.users.admin-password=Adm1n-secret-pw",
      "app.seed.users.customer-password=Cust0mer-secret-pw"
    })
class UserSeedIT extends AbstractIntegrationTest {

  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String ADMIN_EMAIL = "admin-" + RUN + "@seed.flashopify.local";
  private static final Path CSV = tempCsv();

  private static Path tempCsv() {
    try {
      return Files.createTempDirectory("userseed").resolve("k6/users.csv");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void users(DynamicPropertyRegistry registry) {
    registry.add("app.seed.users.admin-email", () -> ADMIN_EMAIL);
    registry.add("app.seed.users.email-pattern", () -> "it" + RUN + "-%04d@seed.flashopify.local");
    registry.add("app.seed.users.export-path", CSV::toString);
  }

  @Autowired UserSeeder seeder;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  @Test
  void seedsIdempotentlyAndTheExportedCredentialsLogIn() throws Exception {
    UserSeedReport first = seeder.run();

    assertThat(first.adminCreated()).isTrue();
    assertThat(first.created()).isEqualTo(50);
    assertThat(first.skipped()).isZero();
    assertThat(first.exported()).isEqualTo(50);
    assertThat(count("SELECT COUNT(*) FROM users WHERE email = ?", ADMIN_EMAIL)).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM users WHERE email LIKE ?", "it" + RUN + "-%"))
        .isEqualTo(50);
    assertThat(
            count(
                "SELECT COUNT(*) FROM users u WHERE u.email LIKE ? AND (SELECT COUNT(*) FROM"
                    + " addresses a WHERE a.user_id = u.id) = 1 AND (SELECT COUNT(*) FROM addresses"
                    + " a WHERE a.user_id = u.id AND a.is_default) = 1",
                "it" + RUN + "-%"))
        .isEqualTo(50);
    assertThat(
            count(
                "SELECT COUNT(DISTINCT password_hash) FROM users WHERE email LIKE ?",
                "it" + RUN + "-%"))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, ADMIN_EMAIL))
        .startsWith("$2")
        .doesNotContain("Adm1n-secret-pw");
    List<String> csv1 = Files.readAllLines(CSV);
    assertThat(csv1).hasSize(51);
    assertThat(csv1.get(0)).isEqualTo("userId,email,password");

    UserSeedReport second = seeder.run();

    assertThat(second.adminCreated()).isFalse();
    assertThat(second.created()).isZero();
    assertThat(second.skipped()).isEqualTo(50);
    assertThat(count("SELECT COUNT(*) FROM users WHERE email LIKE ?", "it" + RUN + "-%"))
        .isEqualTo(50);
    assertThat(
            count(
                "SELECT COUNT(*) FROM addresses a JOIN users u ON u.id = a.user_id"
                    + " WHERE u.email LIKE ?",
                "it" + RUN + "-%"))
        .isEqualTo(50);
    assertThat(Files.readAllLines(CSV)).isEqualTo(csv1);

    String[] row = csv1.get(1).split(",");
    assertThat(row[0]).isEqualTo(String.valueOf(id(row[1])));
    login(row[1], row[2]).andExpect(status().isOk());
    login(row[1], "wrong-password").andExpect(status().isUnauthorized());

    String body =
        login(ADMIN_EMAIL, "Adm1n-secret-pw")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = mapper.readTree(body).get("accessToken").asText();
    JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    assertThat(claims.get(JwtConfig.CLAIM_ROLE).asText()).isEqualTo("ADMIN");
    assertThat(new String(Files.readAllBytes(CSV), StandardCharsets.UTF_8))
        .doesNotContain(ADMIN_EMAIL);
  }

  private org.springframework.test.web.servlet.ResultActions login(String email, String password)
      throws Exception {
    return mvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                mapper.writeValueAsString(java.util.Map.of("email", email, "password", password))));
  }

  private long id(String email) {
    return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }
}
