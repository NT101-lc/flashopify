package com.flashopify.admin.seed;

import com.flashopify.admin.seed.dto.DummyJsonCategory;
import com.flashopify.admin.seed.dto.DummyJsonProductPage;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/** Thin HTTP client for DummyJSON and for downloading the images it points at. */
public class DummyJsonClient {

  /** Downloaded bytes with the Content-Type the server announced (may be null). */
  public record Download(byte[] content, String contentType) {}

  private final RestClient client;
  private final long maxBytes;

  public DummyJsonClient(RestClient.Builder builder, SeedProperties props) {
    this.client = builder.baseUrl(props.dummyjson().baseUrl()).build();
    this.maxBytes = props.image().maxBytes();
  }

  public List<DummyJsonCategory> categories() {
    return client
        .get()
        .uri("/products/categories")
        .retrieve()
        .body(new ParameterizedTypeReference<List<DummyJsonCategory>>() {});
  }

  public DummyJsonProductPage products(int skip, int limit) {
    return client
        .get()
        .uri("/products?limit={limit}&skip={skip}", limit, skip)
        .retrieve()
        .body(DummyJsonProductPage.class);
  }

  /**
   * Downloads an absolute URL, reading at most {@code maxBytes}.
   *
   * @throws IOException on HTTP status 400 and above, or when the body exceeds the limit
   */
  public Download download(String absoluteUrl) throws IOException {
    return client
        .get()
        .uri(URI.create(absoluteUrl))
        .exchange(
            (request, response) -> {
              if (response.getStatusCode().isError()) {
                throw new IOException("HTTP " + response.getStatusCode().value());
              }
              int limit = (int) Math.min(maxBytes + 1, Integer.MAX_VALUE);
              byte[] body = response.getBody().readNBytes(limit);
              if (body.length > maxBytes) {
                throw new IOException("image larger than " + maxBytes + " bytes");
              }
              return new Download(body, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
            });
  }
}
