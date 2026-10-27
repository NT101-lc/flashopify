package com.flashopify.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Expected business failure; turned into an {@link ErrorResponse} by the global handler. */
@Getter
public class BusinessException extends RuntimeException {

  private final ErrorCode code;
  private final HttpStatus status;

  public BusinessException(ErrorCode code, String message) {
    this(code, code.getStatus(), message);
  }

  public BusinessException(ErrorCode code, HttpStatus status, String message) {
    super(message);
    this.code = code;
    this.status = status;
  }
}
