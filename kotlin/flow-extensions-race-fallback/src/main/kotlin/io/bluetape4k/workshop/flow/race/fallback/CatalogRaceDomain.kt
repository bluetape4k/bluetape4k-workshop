package io.bluetape4k.workshop.flow.race.fallback

import java.io.Serializable

/**
 * Reference-data source participating in race, fallback, and merge scenarios.
 */
enum class CatalogSource {
    CACHE,
    REPLICA,
    REMOTE_API,
    BACKUP_API,
}

/**
 * Freshness or completeness marker attached to a catalog source result.
 */
enum class SourceQuality {
    FRESH,
    STALE,
    PARTIAL,
}

/**
 * Small reference-data item used by the Flow composition examples.
 */
data class CatalogItem(
    val sku: String,
    val name: String,
    val priceCents: Long,
    val attributes: Map<String, String> = emptyMap(),
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Value emitted by each source Flow.
 */
data class SourceResult(
    val source: CatalogSource,
    val item: CatalogItem,
    val latencyMs: Long,
    val quality: SourceQuality,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 2L
    }
}
