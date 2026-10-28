package com.flashopify.admin.seed;

import java.time.Duration;

/** Builds {@link SeedProperties} for unit tests. */
final class SeedTestProps {

  static final long MAX_BYTES = 5L * 1024 * 1024;

  private SeedTestProps() {}

  static SeedProperties of(String baseUrl, int maxRetries, int pageSize) {
    return new SeedProperties(
        true,
        new SeedProperties.Products(true),
        new SeedProperties.Users(
            true,
            "",
            "",
            "Flashopify Admin",
            1000,
            200,
            "customer%04d@seed.flashopify.local",
            "",
            "x.csv",
            false),
        new SeedProperties.Dummyjson(baseUrl, pageSize),
        new SeedProperties.Image(maxRetries, Duration.ZERO, MAX_BYTES, Duration.ofSeconds(10)));
  }

  static SeedProperties of() {
    return of("https://dummyjson.com", 3, 100);
  }
}
