package com.flashopify.common.exception;

import com.flashopify.common.exception.ErrorResponse.FieldError;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every exception thrown by a controller to an {@link ErrorResponse}. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
    return build(ex.getStatus(), ex.getCode(), ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
    List<FieldError> fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
            .toList();
    return build(
        HttpStatus.BAD_REQUEST,
        ErrorCode.VALIDATION_ERROR,
        "Request validation failed",
        fieldErrors);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
    List<FieldError> fieldErrors =
        ex.getConstraintViolations().stream()
            .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage(), null))
            .toList();
    return build(
        HttpStatus.BAD_REQUEST,
        ErrorCode.VALIDATION_ERROR,
        "Request validation failed",
        fieldErrors);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
    if (IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(ex.getHeaderName())) {
      return build(
          HttpStatus.BAD_REQUEST,
          ErrorCode.IDEMPOTENCY_KEY_MISSING,
          "Header Idempotency-Key is required");
    }
    return build(
        HttpStatus.BAD_REQUEST,
        ErrorCode.MALFORMED_REQUEST,
        "Missing header " + ex.getHeaderName());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ErrorResponse> handleMalformed(Exception ex) {
    return build(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "Malformed request");
  }

  // The contract has no generic NOT_FOUND / METHOD_NOT_ALLOWED code, so routing errors reuse
  // MALFORMED_REQUEST with the proper HTTP status.
  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
    return build(HttpStatus.NOT_FOUND, ErrorCode.MALFORMED_REQUEST, "No endpoint at this path");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ErrorResponse> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex) {
    return build(
        HttpStatus.METHOD_NOT_ALLOWED,
        ErrorCode.MALFORMED_REQUEST,
        "Method " + ex.getMethod() + " is not supported here");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
    return build(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        ErrorCode.UNSUPPORTED_MEDIA_TYPE,
        "Unsupported content type");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
    return build(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.FILE_TOO_LARGE, "File is too large");
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
    return build(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication required");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    return build(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Access denied");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    return build(
        HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected server error");
  }

  private static ResponseEntity<ErrorResponse> build(
      HttpStatus status, ErrorCode code, String message) {
    return build(status, code, message, List.of());
  }

  private static ResponseEntity<ErrorResponse> build(
      HttpStatus status, ErrorCode code, String message, List<FieldError> fieldErrors) {
    return ResponseEntity.status(status).body(ErrorResponse.of(code, message, fieldErrors));
  }
}
