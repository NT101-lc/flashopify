package com.flashopify.product.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashopify.common.security.JsonErrorWriter;
import com.flashopify.common.security.JwtConfig;
import com.flashopify.common.security.SecurityConfig;
import com.flashopify.product.service.CatalogService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({CatalogController.class, CategoryController.class})
@Import({SecurityConfig.class, JwtConfig.class, JsonErrorWriter.class})
@ActiveProfiles("test")
class CatalogControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean CatalogService catalogService;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/products",
        "/api/v1/products/brands",
        "/api/v1/categories",
        "/api/v1/products/some-slug"
      })
  void anonymousCanReadCatalog(String path) throws Exception {
    org.mockito.Mockito.when(catalogService.categoryTree()).thenReturn(List.of());
    mvc.perform(get(path)).andExpect(status().isOk());
  }

  @ParameterizedTest
  @ValueSource(strings = {"price,asc;DROP TABLE", "id,desc", "", "price,ASC", "price"})
  void invalidSortIsRejected(String sort) throws Exception {
    mvc.perform(get("/api/v1/products").param("sort", sort))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[?(@.field=='sort')]").exists());
  }

  @ParameterizedTest
  @ValueSource(strings = {"createdAt,desc", "price,asc", "price,desc", "rating,desc", "name,asc"})
  void whitelistedSortIsAccepted(String sort) throws Exception {
    mvc.perform(get("/api/v1/products").param("sort", sort)).andExpect(status().isOk());
  }

  @Test
  void sizeAboveMaxIsRejected() throws Exception {
    mvc.perform(get("/api/v1/products").param("size", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[?(@.field=='size')]").exists());
  }

  @Test
  void negativePageIsRejected() throws Exception {
    mvc.perform(get("/api/v1/products").param("page", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[?(@.field=='page')]").exists());
  }

  @Test
  void ratingOutOfRangeIsRejected() throws Exception {
    mvc.perform(get("/api/v1/products").param("minRating", "5.5"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
