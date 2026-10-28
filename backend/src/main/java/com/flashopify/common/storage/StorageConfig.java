package com.flashopify.common.storage;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Builds the S3 client and presigner from {@link StorageProperties}; no provider switch. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

  @Bean(destroyMethod = "close")
  public S3Client s3Client(StorageProperties props) {
    var builder =
        S3Client.builder()
            .region(Region.of(props.region()))
            .credentialsProvider(credentials(props))
            .serviceConfiguration(s3Configuration(props))
            // Only compute checksums when required: keeps MinIO compatibility simple.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
    if (StorageProperties.hasText(props.endpoint())) {
      builder.endpointOverride(URI.create(props.endpoint()));
    }
    return builder.build();
  }

  @Bean(destroyMethod = "close")
  public S3Presigner s3Presigner(StorageProperties props) {
    var builder =
        S3Presigner.builder()
            .region(Region.of(props.region()))
            .credentialsProvider(credentials(props))
            .serviceConfiguration(s3Configuration(props));
    String host =
        StorageProperties.hasText(props.presignEndpoint())
            ? props.presignEndpoint()
            : props.endpoint();
    if (StorageProperties.hasText(host)) {
      builder.endpointOverride(URI.create(host));
    }
    return builder.build();
  }

  @Bean
  public ObjectStorage objectStorage(S3Client client, S3Presigner presigner, StorageProperties p) {
    return new S3ObjectStorage(client, presigner, p);
  }

  static AwsCredentialsProvider credentials(StorageProperties props) {
    if (StorageProperties.hasText(props.accessKey())
        && StorageProperties.hasText(props.secretKey())) {
      return StaticCredentialsProvider.create(
          AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    }
    return DefaultCredentialsProvider.create();
  }

  static S3Configuration s3Configuration(StorageProperties props) {
    return S3Configuration.builder().pathStyleAccessEnabled(props.pathStyleAccess()).build();
  }
}
