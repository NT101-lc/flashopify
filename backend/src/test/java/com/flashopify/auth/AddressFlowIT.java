package com.flashopify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class AddressFlowIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "S3cure!pass";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  private record Session(long userId, String accessToken) {}

  private Session newUser() throws Exception {
    String email = "user-" + UUID.randomUUID() + "@example.com";
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("email", email, "password", PASSWORD, "fullName", "Test User"))))
        .andExpect(status().isCreated());
    JsonNode tokens =
        mapper.readTree(
            mvc.perform(
                    post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return new Session(
        tokens.get("user").get("id").asLong(), "Bearer " + tokens.get("accessToken").asText());
  }

  private String body(String line, Boolean isDefault) throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("receiverName", "An");
    m.put("phone", "0901234567");
    m.put("addressLine", line);
    m.put("city", "Ho Chi Minh");
    if (isDefault != null) {
      m.put("isDefault", isDefault);
    }
    return mapper.writeValueAsString(m);
  }

  private ResultActions create(Session s, String line, Boolean isDefault) throws Exception {
    return mvc.perform(
        post("/api/v1/me/addresses")
            .header("Authorization", s.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(line, isDefault)));
  }

  private long createId(Session s, String line, Boolean isDefault) throws Exception {
    return mapper
        .readTree(
            create(s, line, isDefault)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("id")
        .asLong();
  }

  private int defaultCount(Session s) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM addresses WHERE user_id = ? AND is_default = TRUE",
        Integer.class,
        s.userId());
  }

  private long defaultId(Session s) {
    return jdbc.queryForObject(
        "SELECT id FROM addresses WHERE user_id = ? AND is_default = TRUE", Long.class, s.userId());
  }

  @Test
  void firstAddressIsDefaultAndSwitchingKeepsExactlyOneDefault() throws Exception {
    Session s = newUser();

    long first = createId(s, "first", false);
    assertThat(defaultId(s)).isEqualTo(first);

    long second = createId(s, "second", true);
    assertThat(defaultCount(s)).isEqualTo(1);
    assertThat(defaultId(s)).isEqualTo(second);

    long third = createId(s, "third", false);
    assertThat(defaultId(s)).isEqualTo(second);

    mvc.perform(
            put("/api/v1/me/addresses/" + third)
                .header("Authorization", s.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("third-updated", true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isDefault").value(true))
        .andExpect(jsonPath("$.addressLine").value("third-updated"));
    assertThat(defaultCount(s)).isEqualTo(1);
    assertThat(defaultId(s)).isEqualTo(third);

    mvc.perform(get("/api/v1/me/addresses").header("Authorization", s.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(third))
        .andExpect(jsonPath("$[0].isDefault").value(true));
  }

  @Test
  void deletingTheDefaultPromotesNewestRemaining() throws Exception {
    Session s = newUser();
    long a = createId(s, "a", true);
    long b = createId(s, "b", false);
    long c = createId(s, "c", false);
    assertThat(defaultId(s)).isEqualTo(a);

    mvc.perform(delete("/api/v1/me/addresses/" + a).header("Authorization", s.accessToken()))
        .andExpect(status().isNoContent());

    assertThat(defaultCount(s)).isEqualTo(1);
    assertThat(defaultId(s)).isEqualTo(c);
    mvc.perform(delete("/api/v1/me/addresses/" + b).header("Authorization", s.accessToken()))
        .andExpect(status().isNoContent());
    assertThat(defaultId(s)).isEqualTo(c);
  }

  @Test
  void otherUsersAddressIs404ForUpdateAndDeleteAndStaysIntact() throws Exception {
    Session alice = newUser();
    Session bob = newUser();
    long aliceAddress = createId(alice, "alice home", true);

    mvc.perform(
            put("/api/v1/me/addresses/" + aliceAddress)
                .header("Authorization", bob.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("hacked", true)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
    mvc.perform(
            delete("/api/v1/me/addresses/" + aliceAddress)
                .header("Authorization", bob.accessToken()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
    mvc.perform(get("/api/v1/me/addresses").header("Authorization", bob.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    String line =
        jdbc.queryForObject(
            "SELECT address_line FROM addresses WHERE id = ?", String.class, aliceAddress);
    assertThat(line).isEqualTo("alice home");
  }

  @Test
  void invalidAddressBodyIs400AndMissingAddressIs404() throws Exception {
    Session s = newUser();

    mvc.perform(
            post("/api/v1/me/addresses")
                .header("Authorization", s.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"receiverName\":\"\",\"phone\":\"abc\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(delete("/api/v1/me/addresses/999999999").header("Authorization", s.accessToken()))
        .andExpect(status().isNotFound());
  }

  @Test
  void profileGetAndPatch() throws Exception {
    Session s = newUser();

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/me")
                .header("Authorization", s.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"New Name\",\"phone\":\"0909999999\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fullName").value("New Name"))
        .andExpect(jsonPath("$.phone").value("0909999999"));
    mvc.perform(get("/api/v1/me").header("Authorization", s.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(s.userId()))
        .andExpect(jsonPath("$.fullName").value("New Name"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/me")
                .header("Authorization", s.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"bad\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void tenConcurrentDefaultCreatesLeaveExactlyOneDefault() throws Exception {
    Session s = newUser();
    createId(s, "seed", true);
    int threads = 10;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        String line = "concurrent-" + i;
        Callable<Integer> task =
            () -> {
              ready.countDown();
              go.await();
              return create(s, line, true).andReturn().getResponse().getStatus();
            };
        results.add(pool.submit(task));
      }
      ready.await();
      go.countDown();
      for (Future<Integer> f : results) {
        assertThat(f.get()).isEqualTo(201);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(defaultCount(s)).isEqualTo(1);
    Integer total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM addresses WHERE user_id = ?", Integer.class, s.userId());
    assertThat(total).isEqualTo(threads + 1);
  }

  @Test
  void databaseRejectsSecondDefaultForSameUser() throws Exception {
    Session s = newUser();
    createId(s, "one", true);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO addresses (user_id, receiver_name, phone, address_line, city,"
                        + " is_default) VALUES (?, 'x', '0901234567', 'y', 'z', TRUE)",
                    s.userId()))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }
}
