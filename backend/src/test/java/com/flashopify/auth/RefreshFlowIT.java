package com.flashopify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class RefreshFlowIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "S3cure!pass";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired StringRedisTemplate redis;

  private JsonNode registerAndLogin() throws Exception {
    String email = "user-" + UUID.randomUUID() + "@example.com";
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("email", email, "password", PASSWORD, "fullName", "Test User"))))
        .andExpect(status().isCreated());
    return login(email, PASSWORD);
  }

  private JsonNode login(String email, String password) throws Exception {
    String body = mapper.writeValueAsString(Map.of("email", email, "password", password));
    return mapper.readTree(
        mvc.perform(
                post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private ResultActions refresh(String refreshToken) throws Exception {
    return mvc.perform(
        post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
  }

  private ResultActions logout(String accessToken, String refreshToken) throws Exception {
    return mvc.perform(
        post("/api/v1/auth/logout")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
  }

  private long userId(JsonNode tokens) {
    return tokens.get("user").get("id").asLong();
  }

  private boolean hasAnyRefreshKey(long userId) {
    return !redis.keys("rt:" + userId + ":*").isEmpty();
  }

  @Test
  void refreshRotatesAndReuseOfOldTokenRevokesEverySession() throws Exception {
    JsonNode first = registerAndLogin();
    String r1 = first.get("refreshToken").asText();

    JsonNode second =
        mapper.readTree(
            refresh(r1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    String r2 = second.get("refreshToken").asText();
    assertThat(r2).isNotEqualTo(r1);

    // Reusing the rotated token is treated as theft.
    refresh(r1)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    assertThat(hasAnyRefreshKey(userId(first))).isFalse();

    // The latest legitimate token is dead too.
    refresh(r2).andExpect(status().isUnauthorized());
  }

  @Test
  void newAccessTokenFromRefreshIsAccepted() throws Exception {
    JsonNode first = registerAndLogin();
    JsonNode second =
        mapper.readTree(
            refresh(first.get("refreshToken").asText())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/nothing-here")
                .header("Authorization", "Bearer " + second.get("accessToken").asText()))
        .andExpect(status().isNotFound());
  }

  @Test
  void accessTokenCannotBeUsedAsRefreshToken() throws Exception {
    JsonNode tokens = registerAndLogin();

    refresh(tokens.get("accessToken").asText()).andExpect(status().isUnauthorized());
    assertThat(hasAnyRefreshKey(userId(tokens))).isTrue();
  }

  @Test
  void garbageRefreshTokenIs401() throws Exception {
    refresh("garbage").andExpect(status().isUnauthorized());
  }

  @Test
  void logoutKillsRefreshToken() throws Exception {
    JsonNode tokens = registerAndLogin();
    String refreshToken = tokens.get("refreshToken").asText();

    logout(tokens.get("accessToken").asText(), refreshToken).andExpect(status().isNoContent());

    assertThat(hasAnyRefreshKey(userId(tokens))).isFalse();
    refresh(refreshToken).andExpect(status().isUnauthorized());
  }

  @Test
  void logoutRequiresAuthentication() throws Exception {
    JsonNode tokens = registerAndLogin();

    mvc.perform(
            post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("refreshToken", tokens.get("refreshToken").asText()))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutWithAnotherUsersRefreshTokenIs401AndKeepsIt() throws Exception {
    JsonNode alice = registerAndLogin();
    JsonNode bob = registerAndLogin();

    logout(alice.get("accessToken").asText(), bob.get("refreshToken").asText())
        .andExpect(status().isUnauthorized());

    assertThat(hasAnyRefreshKey(userId(bob))).isTrue();
    refresh(bob.get("refreshToken").asText()).andExpect(status().isOk());
  }

  @Test
  void changePasswordRevokesAllSessionsAndSwitchesPassword() throws Exception {
    String email = "user-" + UUID.randomUUID() + "@example.com";
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("email", email, "password", PASSWORD, "fullName", "Test User"))))
        .andExpect(status().isCreated());
    JsonNode s1 = login(email, PASSWORD);
    JsonNode s2 = login(email, PASSWORD);

    mvc.perform(
            put("/api/v1/me/password")
                .header("Authorization", "Bearer " + s1.get("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("currentPassword", PASSWORD, "newPassword", "N3w!passw0rd"))))
        .andExpect(status().isNoContent());

    refresh(s1.get("refreshToken").asText()).andExpect(status().isUnauthorized());
    refresh(s2.get("refreshToken").asText()).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
        .andExpect(status().isUnauthorized());
    login(email, "N3w!passw0rd");
  }

  @Test
  void changePasswordWithWrongCurrentIs401AndKeepsSessions() throws Exception {
    JsonNode tokens = registerAndLogin();

    mvc.perform(
            put("/api/v1/me/password")
                .header("Authorization", "Bearer " + tokens.get("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("currentPassword", "wrong-pass", "newPassword", "N3w!passw0rd"))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

    assertThat(hasAnyRefreshKey(userId(tokens))).isTrue();
  }

  @Test
  void changePasswordRejectsShortNewPassword() throws Exception {
    JsonNode tokens = registerAndLogin();

    mvc.perform(
            put("/api/v1/me/password")
                .header("Authorization", "Bearer " + tokens.get("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("currentPassword", PASSWORD, "newPassword", "short"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void tenConcurrentRefreshesOfSameTokenYieldExactlyOneSuccess() throws Exception {
    JsonNode tokens = registerAndLogin();
    String refreshToken = tokens.get("refreshToken").asText();
    int threads = 10;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<Integer> task =
            () -> {
              ready.countDown();
              go.await();
              return refresh(refreshToken).andReturn().getResponse().getStatus();
            };
        results.add(pool.submit(task));
      }
      ready.await();
      go.countDown();

      int ok = 0;
      int unauthorized = 0;
      for (Future<Integer> f : results) {
        int code = f.get();
        if (code == 200) {
          ok++;
        } else if (code == 401) {
          unauthorized++;
        }
      }
      assertThat(ok).isEqualTo(1);
      assertThat(unauthorized).isEqualTo(threads - 1);
    } finally {
      pool.shutdownNow();
    }
  }
}
