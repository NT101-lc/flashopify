package com.flashopify.product.domain;

import java.util.Set;

/** Published inside the transaction that changed a product; slugs are every cache key to drop. */
public record ProductChangedEvent(long productId, Set<String> slugs) {}
