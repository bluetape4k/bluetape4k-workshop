package io.bluetape4k.workshop.spring.modulith.boundaries.catalog.internal

import io.bluetape4k.workshop.spring.modulith.boundaries.catalog.api.CatalogItemSnapshot
import io.bluetape4k.workshop.spring.modulith.boundaries.catalog.api.CatalogLookup
import org.springframework.stereotype.Component

/**
 * workshop scenario 를 위한 결정적 catalog data store 입니다.
 */
@Component
class InMemoryCatalogRepository : CatalogLookup {

    private val items = mapOf(
        "course-ddd" to CatalogItemSnapshot(
            sku = "course-ddd",
            name = "Domain-Driven Design Workshop",
            unitPriceCents = 129_00,
            inStock = true,
        ),
        "course-modulith" to CatalogItemSnapshot(
            sku = "course-modulith",
            name = "Spring Modulith Boundary Lab",
            unitPriceCents = 149_00,
            inStock = true,
        ),
        "course-legacy" to CatalogItemSnapshot(
            sku = "course-legacy",
            name = "Legacy Refactoring Clinic",
            unitPriceCents = 99_00,
            inStock = false,
        ),
    )

    override fun findItem(sku: String): CatalogItemSnapshot? =
        items[sku]
}
