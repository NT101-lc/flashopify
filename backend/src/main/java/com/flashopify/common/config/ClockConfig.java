package com.flashopify.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Single {@link Clock} so services can be tested with a fixed time. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
