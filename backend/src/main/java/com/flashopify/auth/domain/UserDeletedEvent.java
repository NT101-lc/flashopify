package com.flashopify.auth.domain;

import java.time.Instant;

/** Published inside the deletion transaction; consumers must react after commit. */
public record UserDeletedEvent(long userId, Instant occurredAt) {}
