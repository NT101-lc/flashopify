package com.flashopify.product.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Catalog settings; {@code app.catalog.cache.detail-ttl} bounds product detail staleness. */
@ConfigurationProperties("app.catalog")
public record CatalogProperties(Cache cache) {

  public CatalogProperties {
    if (cache == null) {
      cache = new Cache(null);
    }
  }

  public record Cache(Duration detailTtl) {

    public Cache {
      if (detailTtl == null) {
        detailTtl = Duration.ofMinutes(10);
      }
    }
  }
}
