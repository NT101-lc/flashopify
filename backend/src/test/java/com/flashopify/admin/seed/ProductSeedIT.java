package com.flashopify.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashopify.support.AbstractStorageIntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * TC-5: the whole seed against a real MySQL and MinIO, with a local HTTP server standing in for
 * DummyJSON. The seed profile turns the web server off, so these tests switch it back on to share
 * the application context setup with the other integration tests.
 */
@ActiveProfiles("seed")
@TestPropertySource(
    properties = {
      "spring.main.web-application-type=servlet",
      "app.seed.auto-run=false",
      "app.seed.dummyjson.page-size=2",
      "app.seed.image.backoff=0ms"
    })
class ProductSeedIT extends AbstractStorageIntegrationTest {

  private static final HttpServer SERVER;
  private static final int PORT;
  private static final ConcurrentLinkedQueue<String> IMAGE_REQUESTS = new ConcurrentLinkedQueue<>();
  private static final byte[] PNG_A = png(0xFF0000);
  private static final byte[] PNG_B = png(0x0000FF);

  static {
    try {
      SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
    PORT = SERVER.getAddress().getPort();
    SERVER.createContext("/", ProductSeedIT::handle);
    SERVER.start();
  }

  @DynamicPropertySource
  static void dummyJson(DynamicPropertyRegistry registry) {
    registry.add("app.seed.dummyjson.base-url", () -> "http://localhost:" + PORT);
  }

  @AfterAll
  static void stopServer() {
    SERVER.stop(0);
  }

  @Autowired ProductSeeder seeder;
  @Autowired JdbcTemplate jdbc;

  @Test
  void seedsOnceAndIsIdempotent() {
    IMAGE_REQUESTS.clear();

    SeedReport first = seeder.run();

    assertThat(first.imported()).isEqualTo(3);
    assertThat(first.skipped()).isZero();
    assertThat(first.errors()).isZero();
    assertThat(first.failed()).isFalse();
    assertThat(first.images()).isEqualTo(5);
    assertThat(first.placeholders()).isEqualTo(1);
    // The always-failing image: first attempt + 3 retries.
    assertThat(IMAGE_REQUESTS.stream().filter(p -> p.equals("/img/broken.png")).count())
        .isEqualTo(4);

    assertThat(count("SELECT COUNT(*) FROM products WHERE source_id IN (9001, 9002, 9003)"))
        .isEqualTo(3);
    assertThat(stock(9001)).isEqualTo(10);
    assertThat(stock(9002)).isEqualTo(20);
    assertThat(stock(9003)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT rating FROM products WHERE source_id = 9003", java.math.BigDecimal.class))
        .isEqualByComparingTo("5.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT price FROM products WHERE source_id = 9003", java.math.BigDecimal.class))
        .isEqualByComparingTo("1.01");
    assertThat(
            count(
                "SELECT COUNT(*) FROM product_images i JOIN products p ON p.id = i.product_id"
                    + " WHERE p.source_id = 9001"))
        .isEqualTo(2);

    List<String> urls = storedUrls();
    assertThat(urls).hasSize(3 + 3);
    assertThat(urls).allSatisfy(u -> assertThat(u).startsWith(publicBaseUrl() + "/"));
    assertThat(urls).noneMatch(u -> u.contains("localhost:" + PORT));
    assertThat(urls).noneMatch(u -> u.contains("dummyjson.com"));
    for (String url : urls) {
      assertThat(objectExists(url.substring(publicBaseUrl().length() + 1)))
          .as("object for %s", url)
          .isTrue();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT thumbnail_url FROM products WHERE source_id = 9002", String.class))
        .isEqualTo(publicBaseUrl() + "/products/placeholder.png");
    assertThat(
            jdbc.queryForObject(
                "SELECT thumbnail_url FROM products WHERE source_id = 9001", String.class))
        .isEqualTo(publicBaseUrl() + "/products/src-9001/thumbnail.png");
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM categories WHERE slug = 'seed-it-gadgets'", String.class))
        .isEqualTo("seed-it-gadgets");

    // Second run: nothing new, nothing downloaded.
    IMAGE_REQUESTS.clear();
    SeedReport second = seeder.run();

    assertThat(second.imported()).isZero();
    assertThat(second.skipped()).isEqualTo(3);
    assertThat(second.images()).isZero();
    assertThat(IMAGE_REQUESTS).isEmpty();
    assertThat(count("SELECT COUNT(*) FROM products WHERE source_id IN (9001, 9002, 9003)"))
        .isEqualTo(3);
    assertThat(storedUrls()).hasSize(6);
  }

  private List<String> storedUrls() {
    List<String> urls = new ArrayList<>();
    urls.addAll(
        jdbc.queryForList(
            "SELECT thumbnail_url FROM products WHERE source_id IN (9001, 9002, 9003)",
            String.class));
    urls.addAll(
        jdbc.queryForList(
            "SELECT i.url FROM product_images i JOIN products p ON p.id = i.product_id"
                + " WHERE p.source_id IN (9001, 9002, 9003)",
            String.class));
    return urls;
  }

  private long count(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    return value == null ? 0 : value;
  }

  private long stock(int sourceId) {
    return count(
        "SELECT i.quantity FROM inventory i JOIN products p ON p.id = i.product_id"
            + " WHERE p.source_id = "
            + sourceId);
  }

  private static void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String query = String.valueOf(exchange.getRequestURI().getQuery());
    try {
      if (path.startsWith("/img/")) {
        IMAGE_REQUESTS.add(path);
        switch (path) {
          case "/img/a.png" -> send(exchange, 200, "image/png", PNG_A);
          case "/img/b.png" -> send(exchange, 200, "image/png", PNG_B);
          default -> send(exchange, 500, "text/plain", new byte[0]);
        }
      } else if (path.equals("/products/categories")) {
        send(exchange, 200, "application/json", fixture("categories.json"));
      } else if (path.equals("/products")) {
        String file = query.contains("skip=2") ? "products-2.json" : "products-0.json";
        send(exchange, 200, "application/json", fixture(file));
      } else {
        send(exchange, 404, "text/plain", new byte[0]);
      }
    } finally {
      exchange.close();
    }
  }

  private static void send(HttpExchange exchange, int status, String type, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", type);
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      exchange.getResponseBody().write(body);
    }
  }

  private static byte[] fixture(String name) throws IOException {
    try (var in = ProductSeedIT.class.getResourceAsStream("/seed/dummyjson/" + name)) {
      String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return json.replace("${port}", String.valueOf(PORT)).getBytes(StandardCharsets.UTF_8);
    }
  }

  private static byte[] png(int rgb) {
    BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 4; x++) {
      for (int y = 0; y < 4; y++) {
        image.setRGB(x, y, rgb);
      }
    }
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
