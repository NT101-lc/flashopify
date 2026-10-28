package com.flashopify.admin.seed;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the seed job ({@code app.seed.*}, profile {@code seed}).
 *
 * @param autoRun run the seed steps at startup and exit; tests switch it off
 */
@ConfigurationProperties("app.seed")
public record SeedProperties(
    @DefaultValue("true") boolean autoRun,
    @DefaultValue Products products,
    @DefaultValue Dummyjson dummyjson,
    @DefaultValue Image image) {

  /** Product import step. */
  public record Products(@DefaultValue("true") boolean enabled) {}

  /** The DummyJSON API. */
  public record Dummyjson(
      @DefaultValue("https://dummyjson.com") String baseUrl, @DefaultValue("100") int pageSize) {}

  /**
   * Image mirroring: {@code maxRetries} retries after the first attempt, the wait doubling from
   * {@code backoff}.
   */
  public record Image(
      @DefaultValue("3") int maxRetries,
      @DefaultValue("200ms") Duration backoff,
      @DefaultValue("5242880") long maxBytes,
      @DefaultValue("10s") Duration timeout) {}
}
