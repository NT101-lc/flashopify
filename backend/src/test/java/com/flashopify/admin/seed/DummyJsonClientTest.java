package com.flashopify.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.flashopify.admin.seed.dto.DummyJsonCategory;
import com.flashopify.admin.seed.dto.DummyJsonProductPage;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** TC-1. */
class DummyJsonClientTest {

  private MockRestServiceServer server;
  private DummyJsonClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client = new DummyJsonClient(builder, SeedTestProps.of());
  }

  @Test
  void parsesCategoriesAndIgnoresUnknownFields() {
    server
        .expect(requestTo("https://dummyjson.com/products/categories"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                [{"slug":"beauty","name":"Beauty","url":"https://x/beauty","extra":1},
                 {"slug":"laptops","name":"Laptops","url":"https://x/laptops"}]
                """,
                MediaType.APPLICATION_JSON));

    List<DummyJsonCategory> categories = client.categories();

    assertThat(categories).extracting(DummyJsonCategory::slug).containsExactly("beauty", "laptops");
    assertThat(categories.get(0).name()).isEqualTo("Beauty");
    server.verify();
  }

  @Test
  void parsesProductPageAndIgnoresUnknownFields() {
    server
        .expect(requestTo("https://dummyjson.com/products?limit=2&skip=4"))
        .andRespond(
            withSuccess(
                """
                {"products":[{"id":5,"title":"T","description":"D","category":"beauty",
                  "price":9.99,"rating":4.5,"stock":7,"brand":"B","sku":"S-1",
                  "thumbnail":"https://cdn/t.png","images":["https://cdn/1.png"],
                  "reviews":[{"rating":5}],"dimensions":{"width":1}}],
                 "total":194,"skip":4,"limit":2}
                """,
                MediaType.APPLICATION_JSON));

    DummyJsonProductPage page = client.products(4, 2);

    assertThat(page.total()).isEqualTo(194);
    assertThat(page.skip()).isEqualTo(4);
    assertThat(page.products()).hasSize(1);
    var product = page.products().get(0);
    assertThat(product.id()).isEqualTo(5);
    assertThat(product.price()).isEqualTo(9.99);
    assertThat(product.images()).containsExactly("https://cdn/1.png");
    server.verify();
  }

  @Test
  void downloadReturnsBytesAndContentType() throws IOException {
    server
        .expect(requestTo("https://cdn.dummyjson.com/a.png"))
        .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG));

    DummyJsonClient.Download download = client.download("https://cdn.dummyjson.com/a.png");

    assertThat(download.content()).containsExactly(1, 2, 3);
    assertThat(download.contentType()).isEqualTo("image/png");
  }

  @Test
  void downloadFailsOnServerError() {
    server.expect(requestTo("https://cdn.dummyjson.com/a.png")).andRespond(withServerError());

    assertThatThrownBy(() -> client.download("https://cdn.dummyjson.com/a.png"))
        .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
        .hasMessageContaining("HTTP 500");
  }
}
