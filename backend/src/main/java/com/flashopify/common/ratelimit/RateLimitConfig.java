package com.flashopify.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.common.web.HandlerMethodLookup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registers {@link RateLimitFilter} (as a bean here, not a {@code @Component}, so {@code
 * WebMvcTest} slices do not need Redis) and checks at startup that every {@link RateLimited} names
 * a configured policy.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

  /** After Spring Security (-100), before the idempotency filter (0). */
  static final int FILTER_ORDER = -1;

  @Bean
  FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
      HandlerMethodLookup lookup, RateLimiter limiter, ObjectMapper objectMapper) {
    FilterRegistrationBean<RateLimitFilter> registration =
        new FilterRegistrationBean<>(new RateLimitFilter(lookup, limiter, objectMapper));
    registration.setOrder(FILTER_ORDER);
    return registration;
  }

  @Bean
  SmartInitializingSingleton rateLimitPolicyCheck(
      @Qualifier("requestMappingHandlerMapping")
          ObjectProvider<RequestMappingHandlerMapping> mapping,
      RateLimitProperties properties) {
    // No MVC in a non-web context (e.g. a migration-only test): nothing to check.
    return () -> mapping.ifAvailable(m -> checkPolicies(m, properties));
  }

  static void checkPolicies(RequestMappingHandlerMapping mapping, RateLimitProperties properties) {
    mapping
        .getHandlerMethods()
        .values()
        .forEach(
            method -> {
              RateLimited rl = method.getMethodAnnotation(RateLimited.class);
              if (rl != null && !properties.policies().containsKey(rl.value())) {
                throw new IllegalStateException(
                    "@RateLimited(\""
                        + rl.value()
                        + "\") on "
                        + method
                        + " has no app.rate-limit.policies entry");
              }
            });
  }
}
