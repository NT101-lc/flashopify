package com.flashopify.admin.seed;

/** Outcome of a {@link SeedStep}; a failed step makes the job exit with status 1. */
public interface SeedResult {

  boolean failed();
}
