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
    @DefaultValue Users users,
    @DefaultValue Dummyjson dummyjson,
    @DefaultValue Image image) {

  /** Product import step. */
  public record Products(@DefaultValue("true") boolean enabled) {}

  /**
   * Admin and customer accounts. Credentials come from the environment and have no committed
   * default; the export is the k6 credentials file.
   *
   * @param customerPassword the one password shared by every customer (hashed once)
   * @param exportPath CSV with userId,email,password; relative paths resolve against backend/
   * @param exportTokens add an accessToken column to the CSV
   */
  public record Users(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("") String adminEmail,
      @DefaultValue("") String adminPassword,
      @DefaultValue("Flashopify Admin") String adminName,
      @DefaultValue("1000") int customerCount,
      @DefaultValue("200") int chunkSize,
      @DefaultValue("customer%04d@seed.flashopify.local") String emailPattern,
      @DefaultValue("") String customerPassword,
      @DefaultValue("../infra/k6/users.csv") String exportPath,
      @DefaultValue("false") boolean exportTokens) {}

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
