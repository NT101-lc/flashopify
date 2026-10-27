package com.flashopify.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashopify.auth.domain.Role;
import com.flashopify.auth.dto.TokenResponse;
import com.flashopify.auth.dto.UserProfileResponse;
import com.flashopify.auth.service.AuthService;
import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.security.JsonErrorWriter;
import com.flashopify.common.security.JwtConfig;
import com.flashopify.common.security.SecurityConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtConfig.class, JsonErrorWriter.class})
@ActiveProfiles("test")
class AuthControllerTest {

  private static final String REGISTER_BODY =
      """
      {"email":"an@example.com","password":"S3cure!pass","fullName":"An","phone":"0901234567"}
      """;
  private static final String LOGIN_BODY =
      """
      {"email":"an@example.com","password":"S3cure!pass"}
      """;

  @Autowired MockMvc mvc;
  @MockitoBean AuthService authService;

  private static UserProfileResponse profile() {
    return new UserProfileResponse(
        1L,
        "an@example.com",
        "An",
        "0901234567",
        Role.CUSTOMER,
        Instant.parse("2026-10-27T08:00:00Z"));
  }

  @Test
  void registerReturns201WithProfile() throws Exception {
    when(authService.register(any())).thenReturn(profile());

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER_BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.role").value("CUSTOMER"));
  }

  @Test
  void registerInvalidBodyReturns400WithFieldErrors() throws Exception {
    String body =
        "{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"\",\"phone\":\"abc\"}";

    mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.length()").value(4));
  }

  @Test
  void registerDuplicateEmailReturns409() throws Exception {
    when(authService.register(any()))
        .thenThrow(new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "taken"));

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
  }

  @Test
  void loginReturns200WithTokens() throws Exception {
    when(authService.login(any()))
        .thenReturn(new TokenResponse("a", "r", TokenResponse.BEARER, 900, profile()));

    mvc.perform(
            post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("a"))
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresIn").value(900))
        .andExpect(jsonPath("$.user.email").value("an@example.com"));
  }

  @Test
  void loginMissingPasswordReturns400() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"an@example.com\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void loginWrongCredentialsReturns401() throws Exception {
    when(authService.login(any()))
        .thenThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS, "bad"));

    mvc.perform(
            post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }

  @Test
  void loginLockedAccountReturns423() throws Exception {
    when(authService.login(any()))
        .thenThrow(new BusinessException(ErrorCode.ACCOUNT_LOCKED, "locked"));

    mvc.perform(
            post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
  }
}
