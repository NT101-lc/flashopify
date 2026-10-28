package com.flashopify.admin.seed;

/**
 * Totals of the user seed.
 *
 * @param adminCreated true when the admin was created, false when it already existed
 * @param created customers created by this run
 * @param skipped customers that already existed
 * @param exported rows written to the credentials CSV
 */
public record UserSeedReport(
    boolean adminCreated, int created, int skipped, int exported, long durationMs)
    implements SeedResult {

  @Override
  public boolean failed() {
    return false;
  }
}
