package com.flashopify.auth.dto;

/** Outcome of seeding one user: its id and whether this call created it. */
public record SeedUserResult(long userId, boolean created) {}
