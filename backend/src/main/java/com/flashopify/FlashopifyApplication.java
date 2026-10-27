package com.flashopify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// No in-memory default user: authentication will come from the auth module (JWT).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class FlashopifyApplication {

  public static void main(String[] args) {
    SpringApplication.run(FlashopifyApplication.class, args);
  }
}
