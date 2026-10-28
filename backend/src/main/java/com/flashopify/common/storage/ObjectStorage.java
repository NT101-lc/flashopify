package com.flashopify.common.storage;

import java.time.Duration;
import java.util.Optional;

/** Object storage port; MinIO and S3 are the same implementation selected by configuration. */
public interface ObjectStorage {

  /**
   * Presigned PUT whose Content-Type and Content-Length are part of the signature, so the client
   * can only upload exactly what was announced.
   */
  PresignedUpload presignPut(String key, String contentType, long contentLength, Duration ttl);

  /** Stores the bytes under the key with the given Content-Type, replacing any existing object. */
  void put(String key, byte[] content, String contentType);

  /** Metadata of the object, empty when it does not exist. */
  Optional<ObjectMetadata> head(String key);

  void copy(String fromKey, String toKey);

  /** Deleting a missing key is not an error. */
  void delete(String key);

  /** Public (CDN or MinIO) URL of the object. */
  String publicUrl(String key);
}
