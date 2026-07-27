package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.workshop.cache.benchmark.domain.Product

/**
 * Common interface for all 7 cache profile service implementations.
 *
 * All implementations must return the same logical result for the same input,
 * ensuring functional equivalence across cache strategies.
 */
interface ProductCacheService {
    /** Find a product by ID. Returns null when not found. */
    fun findById(id: Long): Product?

    /**
     * Save or update a product.
     *
     * Writer-backed Redisson profiles require an existing stable [Product.id]
     * because the map key is the write contract. Generated-ID inserts are not
     * part of the canonical write-through/write-behind benchmark path.
     */
    fun save(product: Product): Product

    /** Remove a product from cache and storage. */
    fun evict(id: Long)

    /** Clear all cached entries (local and/or remote). */
    fun clearAll()
}
