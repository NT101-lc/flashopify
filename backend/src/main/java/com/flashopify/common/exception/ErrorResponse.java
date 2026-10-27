package com.flashopify.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flashopify.common.web.TraceIdFilter;
import java.time.Instant;
import java.util.List;

/** Error body for every non-2xx response; mirrors {@code ErrorResponse} in openapi.yaml. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
    ErrorCode code,
    String message,
    String traceId,
    Instant timestamp,
    List<FieldError> fieldErrors) {

  public static ErrorResponse of(ErrorCode code, String message) {
    return of(code, message, List.of());
  }

  public static ErrorResponse of(ErrorCode code, String message, List<FieldError> fieldErrors) {
    return new ErrorResponse(
        code, message, TraceIdFilter.currentTraceId(), Instant.now(), fieldErrors);
  }

  /** Mirrors {@code FieldError} in openapi.yaml. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FieldError(String field, String message, Object rejectedValue) {}
}
