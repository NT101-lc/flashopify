package com.flashopify.auth.service;

/** Access and refresh token; expiresIn is the access token lifetime in seconds. */
public record TokenPair(String accessToken, String refreshToken, long expiresIn) {}
