package com.flashopify.admin.seed;

import com.flashopify.common.storage.ObjectStorage;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Copies one remote image into our object storage. Every failure is retried; once the retries are
 * used up the placeholder URL is returned (and a WARN logged), so the seed never stores a remote
 * URL.
 */
@Slf4j
public class ImageMirror {

  private static final Map<String, String> EXTENSIONS =
      Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

  private final DummyJsonClient client;
  private final ObjectStorage storage;
  private final PlaceholderImage placeholder;
  private final SeedProperties.Image settings;
  private final AtomicInteger mirrored = new AtomicInteger();
  private final AtomicInteger placeholders = new AtomicInteger();

  public ImageMirror(
      DummyJsonClient client,
      ObjectStorage storage,
      PlaceholderImage placeholder,
      SeedProperties props) {
    this.client = client;
    this.storage = storage;
    this.placeholder = placeholder;
    this.settings = props.image();
  }

  /**
   * Downloads {@code sourceUrl} and stores it as {@code keyBase + "." + ext}.
   *
   * @return our public URL of the stored image, or of the placeholder
   */
  public String mirror(String sourceUrl, String keyBase) {
    if (sourceUrl != null && !sourceUrl.isBlank()) {
      int attempts = settings.maxRetries() + 1;
      for (int attempt = 1; attempt <= attempts; attempt++) {
        try {
          return download(sourceUrl, keyBase);
        } catch (IOException | RuntimeException e) {
          log.debug(
              "image download failed attempt={}/{} sourceUrl={} cause={}",
              attempt,
              attempts,
              sourceUrl,
              e.toString());
          if (attempt < attempts && !sleep(attempt)) {
            break;
          }
        }
      }
    }
    log.warn("image fallback to placeholder sourceUrl={} key={}", sourceUrl, keyBase);
    placeholders.incrementAndGet();
    return placeholder.url();
  }

  public int mirroredCount() {
    return mirrored.get();
  }

  public int placeholderCount() {
    return placeholders.get();
  }

  private String download(String sourceUrl, String keyBase) throws IOException {
    DummyJsonClient.Download download = client.download(sourceUrl);
    String contentType = contentType(download.contentType(), sourceUrl);
    String ext = EXTENSIONS.get(contentType);
    if (ext == null) {
      throw new IOException("unsupported content type " + download.contentType());
    }
    String key = keyBase + "." + ext;
    storage.put(key, download.content(), contentType);
    mirrored.incrementAndGet();
    return storage.publicUrl(key);
  }

  /** Waits backoff * 2^(attempt-1); false when interrupted. */
  private boolean sleep(int attempt) {
    long millis = settings.backoff().toMillis() << (attempt - 1);
    if (millis <= 0) {
      return true;
    }
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static String contentType(String header, String url) {
    String type = header == null ? "" : header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (type.isEmpty() || type.equals("application/octet-stream")) {
      String path = url.toLowerCase(Locale.ROOT);
      int q = path.indexOf('?');
      path = q < 0 ? path : path.substring(0, q);
      if (path.endsWith(".png")) {
        return "image/png";
      }
      if (path.endsWith(".webp")) {
        return "image/webp";
      }
      if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
        return "image/jpeg";
      }
    }
    return type;
  }
}
