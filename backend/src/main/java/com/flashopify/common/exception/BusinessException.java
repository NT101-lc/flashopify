package com.flashopify.common.exception;

import com.flashopify.common.exception.ErrorResponse.FieldError;
import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Expected business failure; turned into an {@link ErrorResponse} by the global handler. */
@Getter
public class BusinessException extends RuntimeException {

  private final ErrorCode code;
  private final HttpStatus status;
  private final List<FieldError> fieldErrors;

  public BusinessException(ErrorCode code, String message) {
    this(code, code.getStatus(), message);
  }

  public BusinessException(ErrorCode code, HttpStatus status, String message) {
    this(code, status, message, List.of());
  }

  private BusinessException(
      ErrorCode code, HttpStatus status, String message, List<FieldError> fieldErrors) {
    super(message);
    this.code = code;
    this.status = status;
    this.fieldErrors = List.copyOf(fieldErrors);
  }

  /**
   * 400 {@code VALIDATION_ERROR} for rules Bean Validation cannot express (e.g. "in the future").
   */
  public static BusinessException validation(List<FieldError> fieldErrors) {
    return new BusinessException(
        ErrorCode.VALIDATION_ERROR,
        HttpStatus.BAD_REQUEST,
        "Request validation failed",
        fieldErrors);
  }
}
