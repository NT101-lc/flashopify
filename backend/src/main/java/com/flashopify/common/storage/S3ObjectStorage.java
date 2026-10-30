package com.flashopify.common.storage;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** The only {@link ObjectStorage}: AWS SDK v2 against S3 or any S3-compatible server (MinIO). */
public class S3ObjectStorage implements ObjectStorage {

  private final S3Client client;
  private final S3Presigner presigner;
  private final StorageProperties props;

  public S3ObjectStorage(S3Client client, S3Presigner presigner, StorageProperties props) {
    this.client = client;
    this.presigner = presigner;
    this.props = props;
  }

  @Override
  public PresignedUpload presignPut(
      String key, String contentType, long contentLength, Duration ttl) {
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(props.bucket())
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength)
            .build();
    var presigned =
        presigner.presignPutObject(
            PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(put).build());
    return new PresignedUpload(
        presigned.url().toString(), Map.of("Content-Type", contentType), presigned.expiration());
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    client.putObject(
        PutObjectRequest.builder()
            .bucket(props.bucket())
            .key(key)
            .contentType(contentType)
            .contentLength((long) content.length)
            .build(),
        RequestBody.fromBytes(content));
  }

  @Override
  public Optional<ObjectMetadata> head(String key) {
    try {
      var r =
          client.headObject(HeadObjectRequest.builder().bucket(props.bucket()).key(key).build());
      return Optional.of(new ObjectMetadata(r.contentLength(), r.contentType()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public void copy(String fromKey, String toKey) {
    client.copyObject(
        CopyObjectRequest.builder()
            .sourceBucket(props.bucket())
            .sourceKey(fromKey)
            .destinationBucket(props.bucket())
            .destinationKey(toKey)
            .build());
  }

  @Override
  public void delete(String key) {
    client.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket()).key(key).build());
  }

  @Override
  public String publicUrl(String key) {
    return props.publicBaseUrl() + "/" + key;
  }
}
