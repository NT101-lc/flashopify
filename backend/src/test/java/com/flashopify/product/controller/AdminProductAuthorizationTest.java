package com.flashopify.product.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashopify.common.security.JsonErrorWriter;
import com.flashopify.common.security.JwtConfig;
import com.flashopify.common.security.SecurityConfig;
import com.flashopify.product.service.AdminProductService;
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

/** FLS-009 authorization: every admin product operation needs ROLE_ADMIN. */
@WebMvcTest(AdminProductController.class)
@Import({SecurityConfig.class, JwtConfig.class, JsonErrorWriter.class})
@ActiveProfiles("test")
class AdminProductAuthorizationTest {

  private static final String BASE = "/api/v1/admin/products";
  private static final String BODY =
      "{\"categoryId\":1,\"sku\":\"S-1\",\"name\":\"N\",\"slug\":\"s-1\",\"price\":\"1.00\","
          + "\"status\":\"ACTIVE\"}";
  private static final String INVENTORY_BODY = "{\"quantity\":1,\"version\":0}";

  @Autowired MockMvc mvc;
  @MockitoBean AdminProductService service;

  /** The six admin operations, each a fresh request builder. */
  private static List<MockHttpServletRequestBuilder> operations() {
    return List.of(
        get(BASE),
        post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY),
        get(BASE + "/1"),
        put(BASE + "/1").contentType(MediaType.APPLICATION_JSON).content(BODY),
        delete(BASE + "/1"),
        put(BASE + "/1/inventory").contentType(MediaType.APPLICATION_JSON).content(INVENTORY_BODY));
  }

  @Test
  void tc15CustomerGets403OnAllSixAdminOperations() throws Exception {
    for (MockHttpServletRequestBuilder op : operations()) {
      mvc.perform(op.with(AdminProductClient.customer()))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void tc15AnonymousGets401OnAllSixAdminOperations() throws Exception {
    for (MockHttpServletRequestBuilder op : operations()) {
      mvc.perform(op)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
    verifyNoInteractions(service);
  }
}
