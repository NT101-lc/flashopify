package com.flashopify.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link IdempotencyProperties} and {@link IdempotencyFilter}. The filter is declared
 * here rather than as a {@code @Component} so {@code @WebMvcTest} slices (which pick up filter
 * components but not Redis) do not need its dependencies.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

  /** After Spring Security (order -100), so the authenticated user is known. */
  static final int FILTER_ORDER = 0;

  @Bean
  FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
      IdempotentEndpointResolver resolver, IdempotencyStore store, ObjectMapper objectMapper) {
    FilterRegistrationBean<IdempotencyFilter> registration =
        new FilterRegistrationBean<>(new IdempotencyFilter(resolver, store, objectMapper));
    registration.setOrder(FILTER_ORDER);
    return registration;
  }
}
