package com.flashopify.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.product.dto.ProductDetailResponse;
import java.util.Collection;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-aside store for the static part of a product detail (ADR-0008). Redis failures are logged
 * and treated as a miss: the catalog must keep working when Redis is down.
 */
@Slf4j
@Component
public class ProductDetailCache {

  static final String KEY_PREFIX = "product:detail:";

  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final CatalogProperties properties;

  public ProductDetailCache(
      StringRedisTemplate redis, ObjectMapper mapper, CatalogProperties properties) {
    this.redis = redis;
    this.mapper = mapper;
    this.properties = properties;
  }

  public static String key(String slug) {
    return KEY_PREFIX + slug;
  }

  public Optional<ProductDetailResponse> get(String slug) {
    try {
      String json = redis.opsForValue().get(key(slug));
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(mapper.readValue(json, ProductDetailResponse.class));
    } catch (DataAccessException | JsonProcessingException e) {
      log.warn("Product detail cache read failed for slug={}: {}", slug, e.toString());
      return Optional.empty();
    }
  }

  public void put(String slug, ProductDetailResponse detail) {
    try {
      redis
          .opsForValue()
          .set(key(slug), mapper.writeValueAsString(detail), properties.cache().detailTtl());
    } catch (DataAccessException | JsonProcessingException e) {
      log.warn("Product detail cache write failed for slug={}: {}", slug, e.toString());
    }
  }

  /** Call after the DB transaction that changed the product has committed. */
  public void evict(Collection<String> slugs) {
    if (slugs == null || slugs.isEmpty()) {
      return;
    }
    try {
      redis.delete(slugs.stream().map(ProductDetailCache::key).toList());
    } catch (DataAccessException e) {
      log.warn("Product detail cache evict failed for slugs={}: {}", slugs, e.toString());
    }
  }
}
