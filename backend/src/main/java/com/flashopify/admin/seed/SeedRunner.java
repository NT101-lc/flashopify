package com.flashopify.admin.seed;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Runs the enabled seed steps once at startup, then exits with 0 (all good) or 1. */
@Slf4j
@Component
@Profile("seed")
@ConditionalOnProperty(name = "app.seed.auto-run", havingValue = "true", matchIfMissing = true)
public class SeedRunner implements ApplicationRunner {

  private final List<SeedStep> steps;
  private final ConfigurableApplicationContext context;

  public SeedRunner(List<SeedStep> steps, ConfigurableApplicationContext context) {
    this.steps = steps;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean failed = false;
    for (SeedStep step : steps) {
      if (!step.enabled()) {
        log.info("seed step skipped (disabled) name={}", step.name());
        continue;
      }
      try {
        failed |= step.run().failed();
      } catch (RuntimeException e) {
        log.error("seed step failed name={}", step.name(), e);
        failed = true;
      }
    }
    int code = failed ? 1 : 0;
    log.info("seed finished exit={}", code);
    System.exit(SpringApplication.exit(context, () -> code));
  }
}
