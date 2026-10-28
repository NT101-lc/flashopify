package com.flashopify.common.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashopify.support.AbstractStorageIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TC-7: {@link ObjectStorage#put} against a real MinIO. */
class S3ObjectStoragePutIT extends AbstractStorageIntegrationTest {

  @Autowired ObjectStorage storage;

  @Test
  void putStoresBytesWithContentType() {
    String key = "put-it/" + UUID.randomUUID() + ".png";
    byte[] content = {1, 2, 3, 4, 5};

    storage.put(key, content, "image/png");

    assertThat(storage.head(key))
        .hasValueSatisfying(
            meta -> {
              assertThat(meta.size()).isEqualTo(5);
              assertThat(meta.contentType()).isEqualTo("image/png");
            });
    assertThat(storage.publicUrl(key)).isEqualTo(publicBaseUrl() + "/" + key);
  }

  @Test
  void putOverwritesAnExistingObject() {
    String key = "put-it/" + UUID.randomUUID() + ".webp";
    storage.put(key, new byte[] {1}, "image/png");

    storage.put(key, new byte[] {1, 2, 3}, "image/webp");

    assertThat(storage.head(key))
        .hasValueSatisfying(
            meta -> {
              assertThat(meta.size()).isEqualTo(3);
              assertThat(meta.contentType()).isEqualTo("image/webp");
            });
  }
}
