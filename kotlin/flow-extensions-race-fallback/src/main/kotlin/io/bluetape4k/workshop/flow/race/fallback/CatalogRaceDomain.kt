package io.bluetape4k.workshop.flow.race.fallback

import java.io.Serializable

/**
 * race, fallback, merge scenario 에 참여하는 reference-data source 입니다.
 */
enum class CatalogSource {
    CACHE,
    REPLICA,
    REMOTE_API,
    BACKUP_API,
}

/**
 * catalog source result 에 붙는 freshness 또는 completeness marker 입니다.
 */
enum class SourceQuality {
    FRESH,
    STALE,
    PARTIAL,
}

/**
 * Flow composition 예제에서 사용하는 작은 reference-data item 입니다.
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
 * 각 source Flow 가 방출하는 값입니다.
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
