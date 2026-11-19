package com.flashopify.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.support.AdminProductClient.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Thin client over the admin flash-sale API for integration tests. Requests are signed as a real
 * ADMIN user (campaigns reference users.id). Thread-safe like {@link AdminProductClient}.
 */
public final class AdminFlashSaleClient {

  public static final String BASE = "/api/v1/admin/flash-sales";

  private final MockMvc mvc;
  private final ObjectMapper mapper;
  private final RequestPostProcessor admin;

  public AdminFlashSaleClient(MockMvc mvc, ObjectMapper mapper, long adminUserId) {
    this.mvc = mvc;
    this.mapper = mapper;
    this.admin =
        jwt()
            .jwt(j -> j.subject(String.valueOf(adminUserId)))
            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  /** One {@code CampaignItemRequest}. */
  public static Map<String, Object> item(long productId, int totalStock) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("productId", productId);
    m.put("salePrice", "9.99");
    m.put("totalStock", totalStock);
    m.put("limitPerUser", 1);
    return m;
  }

  /** A {@code CampaignUpsertRequest} starting {@code startIn} from now and lasting one hour. */
  public static Map<String, Object> campaign(
      String name, Duration startIn, List<Map<String, Object>> items) {
    Instant start = Instant.now().plus(startIn);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("startTime", start.toString());
    m.put("endTime", start.plus(Duration.ofHours(1)).toString());
    m.put("items", new ArrayList<>(items));
    return m;
  }

  public ResultActions create(Map<String, ?> body) throws Exception {
    return send(post(BASE), body);
  }

  public ResultActions update(long id, Map<String, ?> body) throws Exception {
    return send(put(BASE + "/" + id), body);
  }

  public ResultActions cancel(long id) throws Exception {
    return mvc.perform(post(BASE + "/" + id + "/cancel").with(admin));
  }

  public ResultActions get(long id) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.get(BASE + "/" + id).with(admin));
  }

  public ResultActions list(String query) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.get(BASE + query).with(admin));
  }

  public JsonNode parse(ResultActions result) throws Exception {
    return mapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  /** Reads status, error code and body without asserting, for use from worker threads. */
  public Outcome outcome(ResultActions result) throws Exception {
    var mvcResult = result.andReturn();
    String content = mvcResult.getResponse().getContentAsString();
    JsonNode body = content.isBlank() ? mapper.createObjectNode() : mapper.readTree(content);
    return new Outcome(mvcResult.getResponse().getStatus(), body.path("code").asText(""), body);
  }

  private ResultActions send(MockHttpServletRequestBuilder req, Object body) throws Exception {
    return mvc.perform(
        req.with(admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body)));
  }
}
