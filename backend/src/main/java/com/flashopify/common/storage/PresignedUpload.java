package com.flashopify.common.storage;

import java.time.Instant;
import java.util.Map;

/** A presigned PUT: the client must send {@code headers} and upload exactly the signed length. */
public record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {}
