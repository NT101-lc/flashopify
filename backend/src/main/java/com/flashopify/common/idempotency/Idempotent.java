package com.flashopify.common.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method that creates an order or performs a purchase. {@link IdempotencyFilter}
 * then requires the {@code Idempotency-Key} header and replays the first response for the same
 * (user, key, request) within the TTL (FLS-018, CLAUDE.md).
 *
 * <p>The endpoint must be authenticated: keys are scoped per user.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {}
