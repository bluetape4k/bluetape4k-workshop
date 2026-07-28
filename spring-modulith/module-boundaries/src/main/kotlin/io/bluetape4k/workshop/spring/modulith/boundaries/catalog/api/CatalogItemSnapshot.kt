package io.bluetape4k.workshop.spring.modulith.boundaries.catalog.api

import java.io.Serializable

/**
 * catalog module 이 export 하는 read-only item data 입니다.
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
