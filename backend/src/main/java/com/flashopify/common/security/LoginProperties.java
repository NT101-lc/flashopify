package com.flashopify.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Account lockout settings: lock after {@code maxFailedAttempts} consecutive failures. */
@ConfigurationProperties("app.security.login")
public record LoginProperties(int maxFailedAttempts, Duration lockDuration) {}
