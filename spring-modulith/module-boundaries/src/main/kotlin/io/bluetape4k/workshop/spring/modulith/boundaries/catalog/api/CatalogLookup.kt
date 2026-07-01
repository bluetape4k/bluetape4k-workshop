package io.bluetape4k.workshop.spring.modulith.boundaries.catalog.api

/**
 * Exported catalog lookup contract used by modules that need product facts.
 */
fun interface CatalogLookup {
    /**
     * Returns a read-only item snapshot for the given SKU, or `null` when the
     * catalog does not expose that item.
     */
    fun findItem(sku: String): CatalogItemSnapshot?
}
