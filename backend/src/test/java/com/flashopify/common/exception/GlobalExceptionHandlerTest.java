package com.flashopify.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            // Same Jackson setup as Spring Boot: ISO-8601 dates instead of numeric timestamps.
            .setMessageConverters(
                new MappingJackson2HttpMessageConverter(
                    Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build()))
            .build();
  }

  @Test
  void businessExceptionUsesStatusAndCodeOfItsErrorCode() throws Exception {
    ErrorCode code = ErrorCode.VALIDATION_ERROR;
    ThrowingController.next = new BusinessException(code, "boom");

    JsonNode body = perform(get("/t/throw"), code.getStatus().value());

    assertThat(body.get("code").asText()).isEqualTo("VALIDATION_ERROR");
    assertThat(body.get("message").asText()).isEqualTo("boom");
    assertEnvelope(body);
  }

  @Test
  void businessExceptionWithExplicitStatusOverridesCodeStatus() throws Exception {
    ThrowingController.next =
        new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, HttpStatus.GONE, "gone");

    JsonNode body = perform(get("/t/throw"), 410);

    assertThat(body.get("code").asText()).isEqualTo("CONCURRENT_MODIFICATION");
    assertEnvelope(body);
  }

  @Test
  void invalidBodyReturnsValidationErrorWithFieldErrors() throws Exception {
    JsonNode body =
        perform(
            post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"),
            400);

    assertThat(body.get("code").asText()).isEqualTo("VALIDATION_ERROR");
    assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("name");
    assertEnvelope(body);
  }

  @Test
  @SuppressWarnings("unchecked")
  void constraintViolationReportsLeafFieldName() throws Exception {
    ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
    Path path = mock(Path.class);
    when(path.toString()).thenReturn("list.page");
    when(violation.getPropertyPath()).thenReturn(path);
    when(violation.getMessage()).thenReturn("must be >= 0");
    ThrowingController.next = new ConstraintViolationException(Set.of(violation));

    JsonNode body = perform(get("/t/throw"), 400);

    assertThat(body.get("code").asText()).isEqualTo("VALIDATION_ERROR");
    assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("page");
    assertThat(body.get("fieldErrors").get(0).get("message").asText()).isEqualTo("must be >= 0");
    assertEnvelope(body);
  }

  @Test
  void malformedJsonReturnsMalformedRequest() throws Exception {
    JsonNode body =
        perform(post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{not json"), 400);

    assertThat(body.get("code").asText()).isEqualTo("MALFORMED_REQUEST");
    assertEnvelope(body);
  }

  @Test
  void lockFailureReturnsConflictConcurrentModification() throws Exception {
    ThrowingController.next = new CannotAcquireLockException("lock wait timeout");

    JsonNode body = perform(get("/t/throw"), 409);

    assertThat(body.get("code").asText()).isEqualTo("CONCURRENT_MODIFICATION");
    assertEnvelope(body);
  }

  @Test
  void unknownExceptionReturnsGenericInternalErrorWithoutLeakingDetails() throws Exception {
    ThrowingController.next = new IllegalStateException("secret db password in message");

    MvcResult result = mockMvc.perform(get("/t/throw")).andReturn();
    String raw = result.getResponse().getContentAsString();
    JsonNode body = objectMapper.readTree(raw);

    assertThat(result.getResponse().getStatus()).isEqualTo(500);
    assertThat(body.get("code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(body.get("message").asText()).isEqualTo("Unexpected server error");
    assertThat(raw)
        .doesNotContain("secret")
        .doesNotContain("IllegalStateException")
        .doesNotContain("at com.flashopify")
        .doesNotContain("stackTrace");
    assertEnvelope(body);
  }

  private JsonNode perform(MockHttpServletRequestBuilder request, int expectedStatus)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private static void assertEnvelope(JsonNode body) {
    assertThat(body.hasNonNull("code")).isTrue();
    assertThat(body.hasNonNull("message")).isTrue();
    assertThat(body.hasNonNull("traceId")).isTrue();
    assertThat(body.get("traceId").asText()).isNotBlank();
    assertThat(body.hasNonNull("timestamp")).isTrue();
    assertThat(Instant.parse(body.get("timestamp").asText())).isNotNull();
  }

  record Payload(@NotBlank String name) {}

  @RestController
  static class ThrowingController {
    static volatile Exception next;

    @GetMapping("/t/throw")
    String throwNext() throws Exception {
      throw next;
    }

    @PostMapping("/t/body")
    String body(@Valid @RequestBody Payload payload) {
      return "ok";
    }
  }
}
