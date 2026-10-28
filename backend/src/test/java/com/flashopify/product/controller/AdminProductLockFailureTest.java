package com.flashopify.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashopify.common.security.JsonErrorWriter;
import com.flashopify.common.security.JwtConfig;
import com.flashopify.common.security.SecurityConfig;
import com.flashopify.product.service.AdminProductService;
import com.flashopify.support.AdminProductClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** F-1: a row-lock wait failure is a retryable 409 with the standard error body, never a 500. */
@WebMvcTest(AdminProductController.class)
@Import({SecurityConfig.class, JwtConfig.class, JsonErrorWriter.class})
@ActiveProfiles("test")
class AdminProductLockFailureTest {

  @Autowired MockMvc mvc;
  @MockitoBean AdminProductService service;

  @Test
  void f1BusyLockMapsTo409ConcurrentModificationWithStandardBody() throws Exception {
    when(service.updateInventory(anyLong(), any()))
        .thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));

    mvc.perform(
            put("/api/v1/admin/products/1/inventory")
                .with(AdminProductClient.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1,\"version\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        .andExpect(jsonPath("$.message").value("Resource is busy, retry"))
        .andExpect(jsonPath("$.timestamp").exists());
  }
}
