package com.flashopify.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Integration tests against a real MinIO (same image as infra/docker/docker-compose.yml). The
 * container is a singleton per JVM; {@code app.storage.*} is pointed at it.
 */
public abstract class AbstractStorageIntegrationTest extends AbstractIntegrationTest {

  protected static final String BUCKET = "test-media";
  protected static final String ACCESS_KEY = "minioadmin";
  protected static final String SECRET_KEY = "minioadmin";

  protected static final GenericContainer<?> MINIO =
      new GenericContainer<>(DockerImageName.parse("cgr.dev/chainguard/minio:latest"))
          .withCommand("server", "/data")
          .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
          .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
          .withExposedPorts(9000)
          .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

  protected static final S3Client S3;

  static {
    MINIO.start();
    S3 =
        S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    S3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
  }

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  protected static String endpoint() {
    return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
  }

  protected static String publicBaseUrl() {
    return endpoint() + "/" + BUCKET;
  }

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add("app.storage.bucket", () -> BUCKET);
    registry.add("app.storage.region", () -> "us-east-1");
    registry.add("app.storage.endpoint", AbstractStorageIntegrationTest::endpoint);
    registry.add("app.storage.path-style-access", () -> "true");
    registry.add("app.storage.access-key", () -> ACCESS_KEY);
    registry.add("app.storage.secret-key", () -> SECRET_KEY);
    registry.add("app.storage.public-base-url", AbstractStorageIntegrationTest::publicBaseUrl);
  }

  /** Puts bytes straight through the SDK (bypassing presigning). */
  protected static void putObject(String key, String contentType, byte[] data) {
    S3.putObject(
        PutObjectRequest.builder().bucket(BUCKET).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(data));
  }

  protected static boolean objectExists(String key) {
    try {
      S3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  /** PUTs to a presigned URL with the given headers and returns the HTTP status. */
  protected static int httpPut(String url, Map<String, String> headers, byte[] body)
      throws IOException, InterruptedException {
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
    headers.forEach(req::header);
    return HTTP.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }
}
