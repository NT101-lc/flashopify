package com.flashopify.common.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless API security (ADR-0007): Bearer access tokens verified by the resource-server filter
 * without touching DB or Redis. Health, API docs, register and login are open; everything else
 * requires authentication.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JsonErrorWriter jsonErrorWriter,
      @Qualifier("accessTokenDecoder") JwtDecoder accessTokenDecoder,
      JwtAuthenticationConverter jwtAuthenticationConverter)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(jsonErrorWriter).accessDeniedHandler(jsonErrorWriter))
        .oauth2ResourceServer(
            o ->
                o.jwt(
                        j ->
                            j.decoder(accessTokenDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                    .authenticationEntryPoint(jsonErrorWriter)
                    .accessDeniedHandler(jsonErrorWriter))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/error")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/v1/me/addresses/**", "/api/v1/me/addresses")
                    .hasRole("CUSTOMER")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/me")
                    .hasRole("CUSTOMER")
                    .requestMatchers("/api/v1/me/**", "/api/v1/auth/logout")
                    .authenticated()
                    .anyRequest()
                    .authenticated());
    return http.build();
  }
}
