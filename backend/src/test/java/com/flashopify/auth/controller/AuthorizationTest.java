package com.flashopify.auth.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashopify.auth.domain.Role;
import com.flashopify.auth.domain.User;
import com.flashopify.auth.mapper.UserMapper;
import com.flashopify.auth.service.AddressService;
import com.flashopify.auth.service.AuthService;
import com.flashopify.auth.service.RefreshTokenStore;
import com.flashopify.auth.service.TokenServiceImpl;
import com.flashopify.auth.service.UserService;
import com.flashopify.common.security.JsonErrorWriter;
import com.flashopify.common.security.JwtConfig;
import com.flashopify.common.security.JwtProperties;
import com.flashopify.common.security.SecurityConfig;
import com.flashopify.support.AdminProbeController;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AdminProbeController.class, AddressController.class, MeController.class})
@Import({SecurityConfig.class, JwtConfig.class, JsonErrorWriter.class})
@ActiveProfiles("test")
class AuthorizationTest {

  private static final String ADMIN_PROBE = "/api/v1/admin/_probe";

  @Autowired MockMvc mvc;
  @Autowired JwtConfig jwtConfig;
  @Autowired JwtProperties jwtProperties;
  @MockitoBean AddressService addressService;
  @MockitoBean UserService userService;
  @MockitoBean AuthService authService;

  private static org.springframework.test.web.servlet.request.RequestPostProcessor as(Role role) {
    return jwt()
        .jwt(j -> j.subject("1"))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
  }

  @Test
  void customerCannotReachAdminEndpoint() throws Exception {
    mvc.perform(get(ADMIN_PROBE).with(as(Role.CUSTOMER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void adminReachesAdminEndpoint() throws Exception {
    mvc.perform(get(ADMIN_PROBE).with(as(Role.ADMIN))).andExpect(status().isOk());
  }

  @Test
  void adminCannotUseCustomerAddresses() throws Exception {
    mvc.perform(get("/api/v1/me/addresses").with(as(Role.ADMIN)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void customerCanListAddresses() throws Exception {
    mvc.perform(get("/api/v1/me/addresses").with(as(Role.CUSTOMER))).andExpect(status().isOk());
  }

  @Test
  void adminCannotDeleteAccountButCanReadProfile() throws Exception {
    mvc.perform(
            delete("/api/v1/me")
                .with(as(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"x\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/me").with(as(Role.ADMIN))).andExpect(status().isOk());
  }

  @Test
  void anonymousGets401WithErrorBody() throws Exception {
    mvc.perform(get("/api/v1/me/addresses"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.timestamp").exists());
    mvc.perform(get(ADMIN_PROBE)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenGetsTokenExpired() throws Exception {
    String token =
        new TokenServiceImpl(
                jwtConfig.jwtEncoder(jwtProperties),
                jwtConfig.refreshTokenDecoder(jwtProperties),
                jwtProperties,
                Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC),
                Mockito.mock(RefreshTokenStore.class),
                Mockito.mock(UserMapper.class))
            .issue(User.builder().id(1L).role(Role.CUSTOMER).build())
            .accessToken();

    mvc.perform(get("/api/v1/me/addresses").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
  }
}
