package com.flashopify.common.storage;

/** Size in bytes and Content-Type of a stored object. */
public record ObjectMetadata(long size, String contentType) {}
