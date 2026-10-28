package com.flashopify.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flashopify.common.storage.ObjectStorage;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** TC-2. */
class ImageMirrorTest {

  private static final String URL = "https://cdn.dummyjson.com/p/1.png";
  private static final String PLACEHOLDER = "http://media/products/placeholder.png";

  private MockRestServiceServer server;
  private ObjectStorage storage;
  private ImageMirror mirror;
  private ListAppender<ILoggingEvent> logs;
  private Logger logger;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    SeedProperties props = SeedTestProps.of();
    DummyJsonClient client = new DummyJsonClient(builder, props);
    storage = mock(ObjectStorage.class);
    when(storage.publicUrl(anyString())).thenAnswer(i -> "http://media/" + i.getArgument(0));
    PlaceholderImage placeholder = mock(PlaceholderImage.class);
    when(placeholder.url()).thenReturn(PLACEHOLDER);
    mirror = new ImageMirror(client, storage, placeholder, props);

    logger = (Logger) LoggerFactory.getLogger(ImageMirror.class);
    logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
  }

  @Test
  void succeedsAfterThreeFailures() {
    server.expect(times(3), requestTo(URL)).andRespond(withServerError());
    server
        .expect(once(), requestTo(URL))
        .andRespond(withSuccess(new byte[] {1, 2}, MediaType.IMAGE_PNG));

    String result = mirror.mirror(URL, "products/src-1/0");

    assertThat(result).isEqualTo("http://media/products/src-1/0.png");
    server.verify();
    verify(storage).put("products/src-1/0.png", new byte[] {1, 2}, "image/png");
    assertThat(mirror.mirroredCount()).isEqualTo(1);
    assertThat(mirror.placeholderCount()).isZero();
    assertThat(warnings()).isEmpty();
  }

  @Test
  void fallsBackToPlaceholderAfterRetriesAreExhausted() {
    server.expect(times(4), requestTo(URL)).andRespond(withServerError());

    String result = mirror.mirror(URL, "products/src-1/0");

    assertThat(result).isEqualTo(PLACEHOLDER);
    server.verify();
    verify(storage, never()).put(anyString(), any(), anyString());
    assertThat(mirror.placeholderCount()).isEqualTo(1);
    List<ILoggingEvent> warnings = warnings();
    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0).getFormattedMessage())
        .contains("image fallback to placeholder")
        .contains("sourceUrl=" + URL)
        .contains("key=products/src-1/0");
  }

  @Test
  void rejectsBodyOverTheSizeLimit() {
    byte[] big = new byte[(int) SeedTestProps.MAX_BYTES + 1024];
    server.expect(times(4), requestTo(URL)).andRespond(withSuccess(big, MediaType.IMAGE_PNG));

    assertThat(mirror.mirror(URL, "products/src-1/0")).isEqualTo(PLACEHOLDER);
    verify(storage, never()).put(anyString(), any(), anyString());
  }

  @Test
  void rejectsNonImageContentType() {
    server
        .expect(times(4), requestTo(URL))
        .andRespond(withSuccess("<html></html>", MediaType.TEXT_HTML));

    assertThat(mirror.mirror(URL, "products/src-1/0")).isEqualTo(PLACEHOLDER);
    verify(storage, never()).put(anyString(), any(), anyString());
  }

  @Test
  void usesTheContentTypeForTheExtension() {
    server
        .expect(requestTo(URL))
        .andRespond(withSuccess(new byte[] {9}, MediaType.parseMediaType("image/webp")));

    assertThat(mirror.mirror(URL, "k/t")).isEqualTo("http://media/k/t.webp");
    verify(storage).put(eq("k/t.webp"), any(), eq("image/webp"));
  }

  @Test
  void missingSourceUrlGoesStraightToPlaceholder() {
    assertThat(mirror.mirror(null, "k/t")).isEqualTo(PLACEHOLDER);
    assertThat(warnings()).hasSize(1);
  }

  private List<ILoggingEvent> warnings() {
    return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
  }
}
