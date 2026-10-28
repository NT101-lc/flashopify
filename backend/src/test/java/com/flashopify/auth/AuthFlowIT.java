package com.flashopify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class AuthFlowIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "S3cure!pass";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  private static String newEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  private ResultActions register(String email) throws Exception {
    String body =
        mapper.writeValueAsString(
            java.util.Map.of("email", email, "password", PASSWORD, "fullName", "Test User"));
    return mvc.perform(
        post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions login(String email, String password) throws Exception {
    String body = mapper.writeValueAsString(java.util.Map.of("email", email, "password", password));
    return mvc.perform(
        post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void registerThenLoginTokenIsAcceptedOnProtectedPath() throws Exception {
    String email = newEmail();
    register(email)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.role").value("CUSTOMER"));

    String response =
        login(email, PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String accessToken = mapper.readTree(response).get("accessToken").asText();

    // No such endpoint yet: 404 proves the token passed authentication (anonymous would be 401).
    mvc.perform(get("/api/v1/nothing-here").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/nothing-here")).andExpect(status().isUnauthorized());
  }

  @Test
  void refreshTokenIsNotAcceptedAsAccessToken() throws Exception {
    String email = newEmail();
    register(email).andExpect(status().isCreated());
    JsonNode tokens =
        mapper.readTree(login(email, PASSWORD).andReturn().getResponse().getContentAsString());

    mvc.perform(
            get("/api/v1/nothing-here")
                .header("Authorization", "Bearer " + tokens.get("refreshToken").asText()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
  }

  @Test
  void duplicateEmailIgnoringCaseReturns409() throws Exception {
    String email = newEmail();
    register(email).andExpect(status().isCreated());

    register(email.toUpperCase())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
  }

  @Test
  void fifthConsecutiveFailureLocksAccountForFifteenMinutes() throws Exception {
    String email = newEmail();
    register(email).andExpect(status().isCreated());

    for (int i = 0; i < 4; i++) {
      login(email, "wrong-password")
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
    login(email, "wrong-password")
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

    // Even the correct password is refused while locked.
    login(email, PASSWORD).andExpect(status().isLocked());

    Instant lockedUntil =
        jdbc.queryForObject(
                "SELECT locked_until FROM users WHERE email = ?", java.sql.Timestamp.class, email)
            .toInstant();
    assertThat(Duration.between(Instant.now(), lockedUntil))
        .isBetween(Duration.ofMinutes(14), Duration.ofMinutes(16));
  }

  @Test
  void successfulLoginResetsFailureCounter() throws Exception {
    String email = newEmail();
    register(email).andExpect(status().isCreated());
    for (int i = 0; i < 3; i++) {
      login(email, "wrong-password").andExpect(status().isUnauthorized());
    }
    assertThat(failedCount(email)).isEqualTo(3);

    login(email, PASSWORD).andExpect(status().isOk());

    assertThat(failedCount(email)).isZero();
  }

  @Test
  void unknownEmailIs401() throws Exception {
    login(newEmail(), PASSWORD)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }

  private int failedCount(String email) {
    return jdbc.queryForObject(
        "SELECT failed_login_count FROM users WHERE email = ?", Integer.class, email);
  }
}
