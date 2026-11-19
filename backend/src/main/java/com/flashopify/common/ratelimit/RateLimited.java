package com.flashopify.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies the named policy of {@code app.rate-limit.policies} to a controller method (FLS-019,
 * SEC-06). Over the limit the request gets 429 {@code RATE_LIMITED} with {@code Retry-After} before
 * the controller, and before {@code @Idempotent} can claim its key. An unknown policy name fails
 * application startup.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

  /** Policy name, e.g. {@code login}, {@code flash-sale-purchase}, {@code ai-chat}. */
  String value();
}
