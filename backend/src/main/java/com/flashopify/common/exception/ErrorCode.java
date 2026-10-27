package com.flashopify.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Business error codes. Must stay in sync with {@code components.schemas.ErrorCode} in
 * docs/api/openapi.yaml.
 */
@Getter
public enum ErrorCode {
  // generic
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
  INVALID_TOKEN(HttpStatus.UNAUTHORIZED),
  TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
  FORBIDDEN(HttpStatus.FORBIDDEN),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
  SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
  // idempotency
  IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST),
  IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT),
  REQUEST_IN_PROGRESS(HttpStatus.CONFLICT),
  // auth / user
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
  ACCOUNT_LOCKED(HttpStatus.LOCKED),
  EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT),
  USER_NOT_FOUND(HttpStatus.NOT_FOUND),
  ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND),
  // catalog / inventory
  PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
  PRODUCT_NOT_AVAILABLE(HttpStatus.CONFLICT),
  CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND),
  DUPLICATE_SKU(HttpStatus.CONFLICT),
  DUPLICATE_SLUG(HttpStatus.CONFLICT),
  INSUFFICIENT_STOCK(HttpStatus.CONFLICT),
  CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
  PRODUCT_IN_ACTIVE_CAMPAIGN(HttpStatus.CONFLICT),
  // images
  IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND),
  IMAGE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
  FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
  OBJECT_NOT_FOUND(HttpStatus.NOT_FOUND),
  // cart
  CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND),
  CART_EMPTY(HttpStatus.UNPROCESSABLE_ENTITY),
  // flash-sale
  CAMPAIGN_NOT_FOUND(HttpStatus.NOT_FOUND),
  FLASH_SALE_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND),
  CAMPAIGN_NOT_ACTIVE(HttpStatus.CONFLICT),
  CAMPAIGN_NOT_EDITABLE(HttpStatus.CONFLICT),
  INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST),
  SOLD_OUT(HttpStatus.CONFLICT),
  LIMIT_EXCEEDED(HttpStatus.CONFLICT),
  // orders / payments
  ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
  INVALID_ORDER_STATE(HttpStatus.CONFLICT),
  ORDER_EXPIRED(HttpStatus.CONFLICT),
  PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
  INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED),
  PAYMENT_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
  // ai
  AI_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
  AI_LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED);

  /** Default HTTP status; a {@link BusinessException} may override it. */
  private final HttpStatus status;

  ErrorCode(HttpStatus status) {
    this.status = status;
  }
}
