package com.flashopify.product.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashopify.common.security.JsonErrorWriter;
import com.flashopify.common.security.JwtConfig;
import com.flashopify.common.security.SecurityConfig;
import com.flashopify.product.service.ProductImageService;
import com.flashopify.support.AdminProductClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** FLS-010 authorization (TC-15) and request validation (TC-2) of the gallery endpoints. */
@WebMvcTest(AdminProductImageController.class)
@Import({SecurityConfig.class, JwtConfig.class, JsonErrorWriter.class})
@ActiveProfiles("test")
class AdminProductImageControllerTest {

  private static final String BASE = "/api/v1/admin/products/1/images";

  @Autowired MockMvc mvc;
  @MockitoBean ProductImageService service;

  private static List<MockHttpServletRequestBuilder> operations() {
    return List.of(
        post(BASE + "/upload-url")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fileName\":\"a.png\",\"contentType\":\"image/png\",\"contentLength\":10}"),
        post(BASE)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"objectKey\":\"products/1/tmp/x.png\"}"),
        put(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[1]}"),
        delete(BASE + "/5"));
  }

  @Test
  void tc15CustomerGets403OnAllFourEndpoints() throws Exception {
    for (MockHttpServletRequestBuilder op : operations()) {
      mvc.perform(op.with(AdminProductClient.customer()))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void tc15AnonymousGets401OnAllFourEndpoints() throws Exception {
    for (MockHttpServletRequestBuilder op : operations()) {
      mvc.perform(op).andExpect(status().isUnauthorized());
    }
    verifyNoInteractions(service);
  }

  @Test
  void tc2UploadUrlMissingOrInvalidFieldsAreValidationErrors() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"contentType\":\"image/png\",\"contentLength\":10}",
            "{\"fileName\":\"a.png\",\"contentLength\":10}",
            "{\"fileName\":\"a.png\",\"contentType\":\"image/png\"}",
            "{\"fileName\":\"a.png\",\"contentType\":\"image/png\",\"contentLength\":0}",
            "{\"fileName\":\"a.png\",\"contentType\":\"image/png\",\"contentLength\":-5}",
            "{\"fileName\":\""
                + "x".repeat(256)
                + "\",\"contentType\":\"image/png\","
                + "\"contentLength\":5}")) {
      mvc.perform(
              post(BASE + "/upload-url")
                  .with(AdminProductClient.admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void addAndReorderRejectInvalidBodies() throws Exception {
    for (String body :
        List.of("{}", "{\"objectKey\":\" \"}", "{\"objectKey\":\"k\",\"sortOrder\":-1}")) {
      mvc.perform(
              post(BASE)
                  .with(AdminProductClient.admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    for (String body :
        List.of("{}", "{\"imageIds\":[1,2,3,4,5,6,7,8,9]}", "{\"imageIds\":[1,null]}")) {
      mvc.perform(
              put(BASE)
                  .with(AdminProductClient.admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    verifyNoInteractions(service);
  }
}
