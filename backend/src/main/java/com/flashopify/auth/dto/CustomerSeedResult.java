package com.flashopify.auth.dto;

import java.util.Map;

/**
 * Outcome of seeding customers.
 *
 * @param userIds user id by email, for every requested customer (new and existing)
 * @param created customers inserted by this call
 * @param skipped customers that already existed
 */
public record CustomerSeedResult(Map<String, Long> userIds, int created, int skipped) {}
