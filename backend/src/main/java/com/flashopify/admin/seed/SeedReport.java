package com.flashopify.admin.seed;

/**
 * Totals of the product seed.
 *
 * @param categories categories present after the run
 * @param imported products created by this run
 * @param skipped products that already existed (by source id)
 * @param errors products that could not be imported
 * @param images images copied into object storage
 * @param placeholders images replaced by the placeholder
 * @param failed true when the seed is not acceptable (a DummyJSON URL is still stored)
 */
public record SeedReport(
    int categories,
    int imported,
    int skipped,
    int errors,
    int images,
    int placeholders,
    long durationMs,
    boolean failed)
    implements SeedResult {}
