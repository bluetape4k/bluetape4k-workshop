package io.bluetape4k.workshop.spring.modulith.boundaries.catalog.api

import java.io.Serializable

/**
 * Read-only item data exported by the catalog module.
 */
data class CatalogItemSnapshot(
    val sku: String,
    val name: String,
    val unitPriceCents: Long,
    val inStock: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
