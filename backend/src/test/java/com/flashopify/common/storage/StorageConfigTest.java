package com.flashopify.common.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** TC-13: MinIO and S3 differ only by configuration values. */
class StorageConfigTest {

  private final StorageConfig config = new StorageConfig();

  private static StorageProperties minio() {
    return new StorageProperties(
        "media",
        "us-east-1",
        "http://localhost:9000",
        null,
        true,
        "minioadmin",
        "minioadmin",
        "http://localhost:9000/media/",
        null);
  }

  private static StorageProperties s3() {
    return new StorageProperties(
        "prod-media",
        "ap-southeast-1",
        "",
        "",
        false,
        "",
        "",
        "https://cdn.example.com",
        Duration.ofMinutes(5));
  }

  @Test
  void minioPropertiesOverrideEndpointUsePathStyleAndStaticCredentials() throws Exception {
    StorageProperties p = minio();
    assertThat(StorageConfig.credentials(p)).isInstanceOf(StaticCredentialsProvider.class);
    assertThat(StorageConfig.s3Configuration(p).pathStyleAccessEnabled()).isTrue();
    assertThat(p.presignTtl()).isEqualTo(Duration.ofMinutes(10));

    try (S3Client client = config.s3Client(p);
        S3Presigner presigner = config.s3Presigner(p)) {
      assertThat(client.serviceClientConfiguration().endpointOverride())
          .contains(URI.create("http://localhost:9000"));
      var url =
          presigner
              .presignPutObject(
                  r ->
                      r.signatureDuration(Duration.ofMinutes(1))
                          .putObjectRequest(
                              b -> b.bucket("media").key("k.png").contentType("image/png")))
              .url();
      assertThat(url.toString()).startsWith("http://localhost:9000/media/k.png?");
    }
    ObjectStorage storage = config.objectStorage(config.s3Client(p), config.s3Presigner(p), p);
    assertThat(storage.publicUrl("products/1/a.webp"))
        .isEqualTo("http://localhost:9000/media/products/1/a.webp");
  }

  @Test
  void presignEndpointOverridesOnlyTheHostInPresignedUrls() throws Exception {
    StorageProperties base = minio();
    StorageProperties p =
        new StorageProperties(
            base.bucket(),
            base.region(),
            "http://minio:9000",
            "http://localhost:9000",
            true,
            "a",
            "b",
            base.publicBaseUrl(),
            null);
    try (S3Client client = config.s3Client(p);
        S3Presigner presigner = config.s3Presigner(p)) {
      assertThat(client.serviceClientConfiguration().endpointOverride())
          .contains(URI.create("http://minio:9000"));
      var url =
          presigner
              .presignPutObject(
                  r ->
                      r.signatureDuration(Duration.ofMinutes(1))
                          .putObjectRequest(b -> b.bucket("media").key("k.png")))
              .url();
      assertThat(url.getHost()).isEqualTo("localhost");
    }
  }

  @Test
  void s3PropertiesHaveNoEndpointOverrideAndUseDefaultCredentials() throws Exception {
    StorageProperties p = s3();
    assertThat(StorageConfig.credentials(p)).isInstanceOf(DefaultCredentialsProvider.class);
    assertThat(StorageConfig.s3Configuration(p).pathStyleAccessEnabled()).isFalse();
    assertThat(p.presignTtl()).isEqualTo(Duration.ofMinutes(5));

    try (S3Client client = config.s3Client(p)) {
      assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
    }
    ObjectStorage storage = new S3ObjectStorage(null, null, p);
    assertThat(storage.publicUrl("products/1/a.webp"))
        .isEqualTo("https://cdn.example.com/products/1/a.webp");
  }
}
