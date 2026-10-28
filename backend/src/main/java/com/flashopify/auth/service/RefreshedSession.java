package com.flashopify.auth.service;

import com.flashopify.auth.domain.User;

/** Result of rotating a refresh token: the owning user and the new token pair. */
public record RefreshedSession(User user, TokenPair tokens) {}
