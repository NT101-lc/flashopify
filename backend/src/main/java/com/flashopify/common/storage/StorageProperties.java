package com.flashopify.common.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object storage settings ({@code app.storage.*}). MinIO vs S3 is decided only by these values: set
 * {@code endpoint} (and usually {@code pathStyleAccess}) for MinIO, leave it blank for AWS S3.
 *
 * @param endpoint S3 API endpoint the backend talks to; blank = AWS default
 * @param presignEndpoint host used in presigned URLs when the browser reaches storage differently
 *     from the backend; blank = same as {@code endpoint}
 * @param accessKey static credentials; blank = AWS default credentials chain
 * @param publicBaseUrl prefix of public object URLs (CDN or MinIO bucket URL)
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(
    String bucket,
    String region,
    String endpoint,
    String presignEndpoint,
    boolean pathStyleAccess,
    String accessKey,
    String secretKey,
    String publicBaseUrl,
    Duration presignTtl) {

  public StorageProperties {
    if (presignTtl == null) {
      presignTtl = Duration.ofMinutes(10);
    }
    if (publicBaseUrl != null && publicBaseUrl.endsWith("/")) {
      publicBaseUrl = publicBaseUrl.substring(0, publicBaseUrl.length() - 1);
    }
  }

  static boolean hasText(String s) {
    return s != null && !s.isBlank();
  }
}
