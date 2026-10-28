package com.flashopify.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Thin client over the admin product API for integration tests. Stateless apart from the injected
 * MockMvc and ObjectMapper, so one instance can be shared by concurrent threads.
 */
public final class AdminProductClient {

  public static final String BASE = "/api/v1/admin/products";

  /** Status code, error code (empty for success) and parsed body of one response. */
  public record Outcome(int status, String code, JsonNode body) {}

  private final MockMvc mvc;
  private final ObjectMapper mapper;

  public AdminProductClient(MockMvc mvc, ObjectMapper mapper) {
    this.mvc = mvc;
    this.mapper = mapper;
  }

  public static RequestPostProcessor admin() {
    return jwt().jwt(j -> j.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  public static RequestPostProcessor customer() {
    return jwt().jwt(j -> j.subject("2")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
  }

  /** A valid ProductUpsertRequest body; callers may overwrite fields before sending. */
  public static Map<String, Object> upsert(
      long categoryId, String sku, String slug, String status, Integer initialStock) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("categoryId", categoryId);
    m.put("sku", sku);
    m.put("name", "Name " + slug);
    m.put("slug", slug);
    m.put("brand", "Brand");
    m.put("price", new BigDecimal("12.50"));
    m.put("status", status);
    if (initialStock != null) {
      m.put("initialStock", initialStock);
    }
    return m;
  }

  public ResultActions create(Map<String, ?> body) throws Exception {
    return send(post(BASE), body);
  }

  public ResultActions update(long id, Map<String, ?> body) throws Exception {
    return send(put(BASE + "/" + id), body);
  }

  public ResultActions putInventory(long id, int quantity, int version) throws Exception {
    return send(
        put(BASE + "/" + id + "/inventory"), Map.of("quantity", quantity, "version", version));
  }

  public ResultActions get(long id) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.get(BASE + "/" + id).with(admin()));
  }

  public ResultActions delete(long id) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.delete(BASE + "/" + id).with(admin()));
  }

  /** Creates an ACTIVE product through the API, asserts 201 and returns its id. */
  public long createOk(long categoryId, String sku, String slug, Integer initialStock)
      throws Exception {
    ResultActions created =
        create(upsert(categoryId, sku, slug, "ACTIVE", initialStock))
            .andExpect(status().isCreated());
    return parse(created).get("id").asLong();
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
        req.with(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body)));
  }
}
