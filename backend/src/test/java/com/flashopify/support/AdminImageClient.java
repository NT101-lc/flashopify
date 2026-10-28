package com.flashopify.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Thin admin client over the gallery API; stateless, so safe to share between threads. */
public final class AdminImageClient {

  private final MockMvc mvc;
  private final ObjectMapper mapper;

  public AdminImageClient(MockMvc mvc, ObjectMapper mapper) {
    this.mvc = mvc;
    this.mapper = mapper;
  }

  private static String base(long productId) {
    return AdminProductClient.BASE + "/" + productId + "/images";
  }

  public ResultActions uploadUrl(long productId, String fileName, String contentType, Object len)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fileName", fileName);
    body.put("contentType", contentType);
    body.put("contentLength", len);
    return send(post(base(productId) + "/upload-url"), body);
  }

  public ResultActions add(long productId, String objectKey, Integer sortOrder, boolean thumb)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("objectKey", objectKey);
    if (sortOrder != null) {
      body.put("sortOrder", sortOrder);
    }
    body.put("setAsThumbnail", thumb);
    return send(post(base(productId)), body);
  }

  public ResultActions reorder(long productId, List<Long> ids) throws Exception {
    return send(put(base(productId)), Map.of("imageIds", ids));
  }

  public ResultActions delete(long productId, long imageId) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.delete(base(productId) + "/" + imageId)
            .with(AdminProductClient.admin()));
  }

  public JsonNode parse(ResultActions result) throws Exception {
    return mapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private ResultActions send(MockHttpServletRequestBuilder req, Object body) throws Exception {
    return mvc.perform(
        req.with(AdminProductClient.admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body)));
  }
}
