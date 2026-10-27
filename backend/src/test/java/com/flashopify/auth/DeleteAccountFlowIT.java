package com.flashopify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class DeleteAccountFlowIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "S3cure!pass";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;

  private ResultActions register(String email) throws Exception {
    return mvc.perform(
        post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                mapper.writeValueAsString(
                    Map.of("email", email, "password", PASSWORD, "fullName", "Test User"))));
  }

  private ResultActions login(String email) throws Exception {
    return mvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))));
  }

  private ResultActions deleteAccount(String accessToken, String password) throws Exception {
    return mvc.perform(
        delete("/api/v1/me")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("password", password))));
  }

  @Test
  void deletionAnonymisesRevokesTokensAndFreesTheEmail() throws Exception {
    String email = "user-" + UUID.randomUUID() + "@example.com";
    register(email).andExpect(status().isCreated());
    JsonNode tokens =
        mapper.readTree(
            login(email).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    long userId = tokens.get("user").get("id").asLong();
    String access = tokens.get("accessToken").asText();
    String refresh = tokens.get("refreshToken").asText();
    mvc.perform(
            post("/api/v1/me/addresses")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"receiverName\":\"An\",\"phone\":\"0901234567\","
                        + "\"addressLine\":\"x\",\"city\":\"y\"}"))
        .andExpect(status().isCreated());
    assertThat(redis.keys("rt:" + userId + ":*")).isNotEmpty();

    deleteAccount(access, "wrong-pass").andExpect(status().isUnauthorized());
    assertThat(redis.keys("rt:" + userId + ":*")).isNotEmpty();

    deleteAccount(access, PASSWORD).andExpect(status().isNoContent());

    Map<String, Object> row =
        jdbc.queryForMap("SELECT email, full_name, phone, status FROM users WHERE id = ?", userId);
    assertThat(row.get("email")).isEqualTo("deleted-" + userId + "@deleted.invalid");
    assertThat(row.get("full_name")).isEqualTo("Deleted user");
    assertThat(row.get("phone")).isNull();
    assertThat(row.get("status")).isEqualTo("DELETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM addresses WHERE user_id = ?", Integer.class, userId))
        .isZero();
    assertThat(redis.keys("rt:" + userId + ":*")).isEmpty();

    login(email).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", refresh))))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    register(email).andExpect(status().isCreated());
  }
}
