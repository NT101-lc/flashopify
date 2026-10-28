package com.flashopify.admin.seed;

/**
 * One stage of the seed job. {@link SeedRunner} runs every enabled step in {@code @Order}; add a
 * new step (for example the user seeder) by registering another bean of this type.
 */
public interface SeedStep {

  String name();

  boolean enabled();

  SeedResult run();
}
