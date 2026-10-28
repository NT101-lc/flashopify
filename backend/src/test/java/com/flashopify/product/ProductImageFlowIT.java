package com.flashopify.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.common.storage.ObjectStorage;
import com.flashopify.product.service.ProductImageServiceImpl;
import com.flashopify.support.AbstractStorageIntegrationTest;
import com.flashopify.support.AdminImageClient;
import com.flashopify.support.AdminProductClient;
import com.flashopify.support.AdminProductClient.Outcome;
import com.flashopify.support.CatalogFixtures;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** FLS-010 end to end against a real MySQL, Redis and MinIO. */
class ProductImageFlowIT extends AbstractStorageIntegrationTest {

  private static final byte[] ONE_KB = new byte[1024];

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @MockitoSpyBean ObjectStorage storage;

  private AdminProductClient admin;
  private AdminImageClient images;
  private long cat;
  private ListAppender<ILoggingEvent> warnings;

  @BeforeEach
  void init() {
    admin = new AdminProductClient(mvc, mapper);
    images = new AdminImageClient(mvc, mapper);
    cat = new CatalogFixtures(jdbc).category("Img " + CatalogFixtures.rnd(), null);
    reset(storage);
  }

  @AfterEach
  void detach() {
    if (warnings != null) {
      logger().detachAppender(warnings);
      warnings = null;
    }
  }

  private static Logger logger() {
    return (Logger) LoggerFactory.getLogger(ProductImageServiceImpl.class);
  }

  // ---- helpers ----

  private long newProduct() throws Exception {
    String sfx = CatalogFixtures.rnd();
    return admin.createOk(cat, "SKU-IMG-" + sfx, "img-" + sfx, 3);
  }

  private String slugOf(long pid) {
    return jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, pid);
  }

  private String thumbnail(long pid) {
    return jdbc.queryForObject(
        "SELECT thumbnail_url FROM products WHERE id = ?", String.class, pid);
  }

  private int imageCount(long pid) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM product_images WHERE product_id = ?", Integer.class, pid);
  }

  private List<Long> idsInOrder(long pid) {
    return jdbc.queryForList(
        "SELECT id FROM product_images WHERE product_id = ? ORDER BY sort_order, id",
        Long.class,
        pid);
  }

  private List<Integer> sortOrders(long pid) {
    return jdbc.queryForList(
        "SELECT sort_order FROM product_images WHERE product_id = ? ORDER BY sort_order, id",
        Integer.class,
        pid);
  }

  /** Inserts a row directly (as the seed job does), without touching storage. */
  private long seedImage(long pid, int order) {
    jdbc.update(
        "INSERT INTO product_images (product_id, url, sort_order) VALUES (?, ?, ?)",
        pid,
        publicBaseUrl() + "/products/" + pid + "/seed-" + UUID.randomUUID() + ".png",
        order);
    return jdbc.queryForObject("SELECT MAX(id) FROM product_images", Long.class);
  }

  /** Puts an object under a valid tmp key straight into MinIO and returns the key. */
  private String putTmp(long pid, String ext, String contentType, byte[] data) {
    String key = "products/" + pid + "/tmp/" + UUID.randomUUID() + "." + ext;
    putObject(key, contentType, data);
    return key;
  }

  /** Full client flow: presign, PUT with the returned headers, return the object key. */
  private String uploadViaPresign(long pid, String type, byte[] data) throws Exception {
    JsonNode r =
        images.parse(images.uploadUrl(pid, "f.bin", type, data.length).andExpect(status().isOk()));
    Map<String, String> headers = new LinkedHashMap<>();
    r.get("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
    assertThat(httpPut(r.get("uploadUrl").asText(), headers, data)).isEqualTo(200);
    return r.get("objectKey").asText();
  }

  private JsonNode register(long pid, String key, Integer order, boolean thumb) throws Exception {
    return images.parse(images.add(pid, key, order, thumb).andExpect(status().isCreated()));
  }

  private void assertError(ResultActions r, int httpStatus, String code) throws Exception {
    r.andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code));
  }

  // ---- TC-4 ----

  @Test
  void tc4PresignedPutThenRegisterStoresPublicUrlAndMovesObject() throws Exception {
    long pid = newProduct();
    JsonNode up =
        images.parse(
            images.uploadUrl(pid, "front.webp", "image/webp", 1024).andExpect(status().isOk()));
    assertThat(up.get("method").asText()).isEqualTo("PUT");
    assertThat(up.get("headers").get("Content-Type").asText()).isEqualTo("image/webp");
    assertThat(up.get("headers").size()).isEqualTo(1);
    String tmpKey = up.get("objectKey").asText();
    assertThat(tmpKey).matches("products/" + pid + "/tmp/[0-9a-f-]{36}\\.webp");
    assertThat(up.get("uploadUrl").asText()).contains(tmpKey);
    assertThat(up.get("expiresAt").asText()).isNotBlank();

    assertThat(httpPut(up.get("uploadUrl").asText(), Map.of("Content-Type", "image/webp"), ONE_KB))
        .isEqualTo(200);
    assertThat(objectExists(tmpKey)).isTrue();

    JsonNode created = register(pid, tmpKey, null, false);
    String url = created.get("url").asText();
    assertThat(url).matches(publicBaseUrl() + "/products/" + pid + "/[0-9a-f-]{36}\\.webp");
    String finalKey = url.substring((publicBaseUrl() + "/").length());
    assertThat(objectExists(finalKey)).isTrue();
    assertThat(objectExists(tmpKey)).as("tmp object removed after commit").isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT url FROM product_images WHERE id = ?",
                String.class,
                created.get("id").asLong()))
        .isEqualTo(url);
    assertThat(created.get("sortOrder").asInt()).isZero();
  }

  // ---- TC-2 / TC-3 (HTTP level) ----

  @Test
  void tc2UnsupportedTypeAndOversizeOnUploadUrlAre400() throws Exception {
    long pid = newProduct();
    assertError(images.uploadUrl(pid, "a.gif", "image/gif", 100), 400, "UNSUPPORTED_MEDIA_TYPE");
    assertError(images.uploadUrl(pid, "a.png", "image/png", 5_242_881L), 400, "FILE_TOO_LARGE");
    images.uploadUrl(pid, "a.png", "image/png", 5_242_880L).andExpect(status().isOk());
    assertError(images.uploadUrl(pid, "", "image/png", 5), 400, "VALIDATION_ERROR");
    assertError(images.uploadUrl(pid, "a.png", "image/png", null), 400, "VALIDATION_ERROR");
  }

  @Test
  void tc3EightImagesBlockBothUploadUrlAndRegister() throws Exception {
    long pid = newProduct();
    for (int i = 0; i < 8; i++) {
      seedImage(pid, i);
    }
    String key = putTmp(pid, "png", "image/png", ONE_KB);
    assertError(images.uploadUrl(pid, "a.png", "image/png", 10), 409, "IMAGE_LIMIT_EXCEEDED");
    assertError(images.add(pid, key, null, false), 409, "IMAGE_LIMIT_EXCEEDED");
    assertThat(imageCount(pid)).isEqualTo(8);
    assertThat(objectExists(key)).as("tmp object untouched").isTrue();
  }

  // ---- TC-5 / TC-6 / TC-7 / TC-8 ----

  @Test
  void tc5ValidKeyNeverUploadedIsObjectNotFound() throws Exception {
    long pid = newProduct();
    String key = "products/" + pid + "/tmp/" + UUID.randomUUID() + ".png";
    assertError(images.add(pid, key, null, false), 400, "OBJECT_NOT_FOUND");
    assertThat(imageCount(pid)).isZero();
  }

  @Test
  void tc6ForeignOrOutOfTmpKeysAreValidationErrors() throws Exception {
    long pid = newProduct();
    long other = newProduct();
    String foreign = putTmp(other, "png", "image/png", ONE_KB);
    assertError(images.add(pid, foreign, null, false), 400, "VALIDATION_ERROR");
    assertThat(objectExists(foreign)).isTrue();
    String uuid = UUID.randomUUID().toString();
    for (String key :
        List.of(
            "products/" + pid + "/" + uuid + ".png",
            "products/" + pid + "/tmp/../" + other + "/tmp/" + uuid + ".png",
            "../etc/passwd")) {
      assertError(images.add(pid, key, null, false), 400, "VALIDATION_ERROR");
    }
    assertThat(imageCount(pid)).isZero();
  }

  @Test
  void tc7OversizedObjectUploadedBehindOurBackIsRejectedAndDeleted() throws Exception {
    long pid = newProduct();
    String key = putTmp(pid, "png", "image/png", new byte[6 * 1024 * 1024]);
    assertError(images.add(pid, key, null, false), 400, "FILE_TOO_LARGE");
    assertThat(objectExists(key)).isFalse();
    assertThat(imageCount(pid)).isZero();
  }

  @Test
  void tc7WrongContentTypeObjectIsRejectedAndDeleted() throws Exception {
    long pid = newProduct();
    String key = putTmp(pid, "png", "text/html", ONE_KB);
    assertError(images.add(pid, key, null, false), 400, "UNSUPPORTED_MEDIA_TYPE");
    assertThat(objectExists(key)).isFalse();
    assertThat(imageCount(pid)).isZero();
  }

  @Test
  void tc8PresignedPutWithDifferentContentTypeOrLengthIsRejectedByStorage() throws Exception {
    long pid = newProduct();
    JsonNode up =
        images.parse(images.uploadUrl(pid, "a.png", "image/png", 1024).andExpect(status().isOk()));
    String url = up.get("uploadUrl").asText();
    String key = up.get("objectKey").asText();

    assertThat(httpPut(url, Map.of("Content-Type", "text/html"), ONE_KB)).isEqualTo(403);
    assertThat(httpPut(url, Map.of("Content-Type", "image/png"), new byte[2048])).isEqualTo(403);
    assertThat(objectExists(key)).isFalse();
    assertThat(httpPut(url, Map.of("Content-Type", "image/png"), ONE_KB)).isEqualTo(200);
    assertThat(objectExists(key)).isTrue();
  }

  // ---- TC-9 ----

  @Test
  void tc9ThumbnailAndSortOrderRules() throws Exception {
    long pid = newProduct();
    assertThat(thumbnail(pid)).isNull();

    JsonNode first = register(pid, uploadViaPresign(pid, "image/png", ONE_KB), null, false);
    assertThat(thumbnail(pid))
        .as("first image becomes thumbnail")
        .isEqualTo(first.get("url").asText());

    JsonNode second = register(pid, uploadViaPresign(pid, "image/jpeg", ONE_KB), null, false);
    assertThat(second.get("sortOrder").asInt()).isEqualTo(1);
    assertThat(thumbnail(pid)).isEqualTo(first.get("url").asText());

    JsonNode third = register(pid, uploadViaPresign(pid, "image/png", ONE_KB), 0, true);
    assertThat(third.get("sortOrder").asInt()).isZero();
    assertThat(thumbnail(pid)).isEqualTo(third.get("url").asText());
    assertThat(idsInOrder(pid))
        .containsExactly(
            third.get("id").asLong(), first.get("id").asLong(), second.get("id").asLong());
    assertThat(sortOrders(pid)).containsExactly(0, 1, 2);

    JsonNode clamped = register(pid, uploadViaPresign(pid, "image/png", ONE_KB), 99, false);
    assertThat(clamped.get("sortOrder").asInt()).isEqualTo(3);
  }

  // ---- TC-10 ----

  @Test
  void tc10ReorderRequiresFullPermutation() throws Exception {
    long pid = newProduct();
    long a = seedImage(pid, 0);
    long b = seedImage(pid, 1);
    long c = seedImage(pid, 2);
    long foreign = seedImage(newProduct(), 0);

    images
        .reorder(pid, List.of(c, a, b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(c))
        .andExpect(jsonPath("$[0].sortOrder").value(0))
        .andExpect(jsonPath("$[1].id").value(a))
        .andExpect(jsonPath("$[1].sortOrder").value(1))
        .andExpect(jsonPath("$[2].id").value(b))
        .andExpect(jsonPath("$[2].sortOrder").value(2));
    assertThat(idsInOrder(pid)).containsExactly(c, a, b);

    for (List<Long> bad :
        List.of(
            List.of(c, a),
            List.of(c, a, b, foreign),
            List.of(c, a, foreign),
            List.of(c, a, a),
            List.of(c, c, a, b))) {
      assertError(images.reorder(pid, bad), 400, "VALIDATION_ERROR");
      assertThat(idsInOrder(pid)).as(bad.toString()).containsExactly(c, a, b);
      assertThat(sortOrders(pid)).containsExactly(0, 1, 2);
    }
  }

  // ---- TC-11 ----

  @Test
  void tc11DeleteCompactsFallsBackThumbnailAndRemovesObjectAfterCommit() throws Exception {
    long pid = newProduct();
    JsonNode a = register(pid, uploadViaPresign(pid, "image/png", ONE_KB), null, false);
    JsonNode b = register(pid, uploadViaPresign(pid, "image/png", ONE_KB), null, false);
    JsonNode c = register(pid, uploadViaPresign(pid, "image/png", ONE_KB), null, false);
    long idA = a.get("id").asLong();
    long idB = b.get("id").asLong();
    long idC = c.get("id").asLong();
    String keyA = a.get("url").asText().substring((publicBaseUrl() + "/").length());
    assertThat(thumbnail(pid)).isEqualTo(a.get("url").asText());
    assertThat(objectExists(keyA)).isTrue();

    AtomicBoolean rowGoneWhenDeleted = new AtomicBoolean();
    doAnswer(
            inv -> {
              // A separate connection only sees committed data: the row must already be gone.
              rowGoneWhenDeleted.set(
                  jdbc.queryForObject(
                          "SELECT COUNT(*) FROM product_images WHERE id = ?", Integer.class, idA)
                      == 0);
              return inv.callRealMethod();
            })
        .when(storage)
        .delete(keyA);

    images.delete(pid, idA).andExpect(status().isNoContent());

    assertThat(rowGoneWhenDeleted).as("object deleted only after commit").isTrue();
    assertThat(objectExists(keyA)).isFalse();
    assertThat(idsInOrder(pid)).containsExactly(idB, idC);
    assertThat(sortOrders(pid)).containsExactly(0, 1);
    assertThat(thumbnail(pid)).as("falls back to next image").isEqualTo(b.get("url").asText());

    // Another product's image id behaves like "not found" and nothing changes.
    long otherPid = newProduct();
    assertError(images.delete(otherPid, idB), 404, "IMAGE_NOT_FOUND");
    assertThat(idsInOrder(pid)).containsExactly(idB, idC);

    // Deleting everything clears the thumbnail.
    images.delete(pid, idB).andExpect(status().isNoContent());
    images.delete(pid, idC).andExpect(status().isNoContent());
    assertThat(thumbnail(pid)).isNull();
    assertThat(imageCount(pid)).isZero();
  }

  @Test
  void tc11StorageDeleteFailureStillReturns204AndIsLogged() throws Exception {
    long pid = newProduct();
    JsonNode a = register(pid, uploadViaPresign(pid, "image/png", ONE_KB), null, false);
    doThrow(new RuntimeException("storage down")).when(storage).delete(anyString());
    warnings = new ListAppender<>();
    warnings.start();
    logger().addAppender(warnings);

    images.delete(pid, a.get("id").asLong()).andExpect(status().isNoContent());

    assertThat(imageCount(pid)).isZero();
    assertThat(
            warnings.list.stream()
                .anyMatch(
                    e ->
                        e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("Could not delete storage object")))
        .isTrue();
  }

  // ---- TC-12 ----

  @Test
  void tc12EightConcurrentRegistrationsOnSevenImagesYieldExactlyOneCreated() throws Exception {
    long pid = newProduct();
    for (int i = 0; i < 7; i++) {
      seedImage(pid, i);
    }
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      keys.add(putTmp(pid, "png", "image/png", ONE_KB));
    }
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Outcome>> futures = new ArrayList<>();
      for (String key : keys) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return admin.outcome(images.add(pid, key, null, false));
                }));
      }
      start.countDown();
      int created = 0;
      int conflicts = 0;
      for (Future<Outcome> f : futures) {
        Outcome o = f.get();
        if (o.status() == 201) {
          created++;
        } else if (o.status() == 409 && o.code().equals("IMAGE_LIMIT_EXCEEDED")) {
          conflicts++;
        }
      }
      assertThat(created).isEqualTo(1);
      assertThat(conflicts).isEqualTo(7);
      assertThat(imageCount(pid)).isEqualTo(8);
      assertThat(sortOrders(pid)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    } finally {
      pool.shutdownNow();
    }
  }

  // ---- TC-14 ----

  @Test
  void tc14PublicDetailReflectsAddReorderAndDelete() throws Exception {
    long pid = newProduct();
    String slug = slugOf(pid);

    assertThat(publicImageIds(slug)).isEmpty();
    JsonNode a = register(pid, uploadViaPresign(pid, "image/png", ONE_KB), null, false);
    assertThat(publicImageIds(slug)).containsExactly(a.get("id").asLong());
    JsonNode detail = publicDetail(slug);
    assertThat(detail.get("thumbnailUrl").asText()).isEqualTo(a.get("url").asText());

    JsonNode b = register(pid, uploadViaPresign(pid, "image/png", ONE_KB), null, false);
    assertThat(publicImageIds(slug)).containsExactly(a.get("id").asLong(), b.get("id").asLong());

    images
        .reorder(pid, List.of(b.get("id").asLong(), a.get("id").asLong()))
        .andExpect(status().isOk());
    assertThat(publicImageIds(slug)).containsExactly(b.get("id").asLong(), a.get("id").asLong());

    images.delete(pid, b.get("id").asLong()).andExpect(status().isNoContent());
    assertThat(publicImageIds(slug)).containsExactly(a.get("id").asLong());
  }

  private JsonNode publicDetail(String slug) throws Exception {
    String body =
        mvc.perform(get("/api/v1/products/" + slug))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(body);
  }

  private List<Long> publicImageIds(String slug) throws Exception {
    List<Long> ids = new ArrayList<>();
    Iterator<JsonNode> it = publicDetail(slug).get("images").elements();
    it.forEachRemaining(n -> ids.add(n.get("id").asLong()));
    return ids;
  }

  // ---- TC-15 ----

  @Test
  void tc15UnknownAndDeletedProductsAreNotFoundOnAllEndpoints() throws Exception {
    long deleted = newProduct();
    admin.delete(deleted).andExpect(status().isNoContent());
    String key = putTmp(deleted, "png", "image/png", ONE_KB);
    long[] ids = {deleted, Long.MAX_VALUE - 1};
    for (long pid : ids) {
      assertError(images.uploadUrl(pid, "a.png", "image/png", 10), 404, "PRODUCT_NOT_FOUND");
      assertError(
          images.add(pid, "products/" + pid + "/tmp/" + UUID.randomUUID() + ".png", null, false),
          404,
          "PRODUCT_NOT_FOUND");
      assertError(images.reorder(pid, List.of()), 404, "PRODUCT_NOT_FOUND");
      assertError(images.delete(pid, 1), 404, "PRODUCT_NOT_FOUND");
    }
    assertThat(objectExists(key)).isTrue();
  }
}
