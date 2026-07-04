package io.bluetape4k.workshop.flow.search.pipeline

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.util.Collections
import java.util.Locale

/**
 * Search matching mode used by the in-memory autocomplete catalog.
 */
enum class SearchMode {
    PREFIX,
    FUZZY,
    EXACT,
}

/**
 * Normalized, non-blank search text.
 *
 * The public factory trims caller input, validates the canonical value, and
 * stores only the normalized text. This class is intentionally not a data class
 * so generated `copy` cannot bypass validation.
 */
class SearchQuery private constructor(
    val text: String,
): Serializable {

    override fun toString(): String = "SearchQuery(text=<redacted>, length=${text.length})"

    override fun equals(other: Any?): Boolean =
        this === other || other is SearchQuery && text == other.text

    override fun hashCode(): Int = text.hashCode()

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(text: String): SearchQuery {
            val normalized = text.trim()
            normalized.requireNotBlank("text")
            normalized.length.requireInRange(1, 64, "text.length")
            return SearchQuery(normalized)
        }
    }
}

/**
 * Search settings captured at the time a request starts.
 *
 * `featureFlags` is defensively copied into an unmodifiable set so later caller
 * mutation cannot change matching or logging behavior.
 */
class SearchSettings private constructor(
    val tenantId: String,
    val locale: Locale,
    val mode: SearchMode,
    val featureFlags: Set<String>,
    val resultLimit: Int,
): Serializable {

    override fun toString(): String =
        "SearchSettings(tenant=<redacted>, locale=$locale, mode=$mode, flags=${featureFlags.size}, resultLimit=$resultLimit)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SearchSettings &&
            tenantId == other.tenantId &&
            locale == other.locale &&
            mode == other.mode &&
            featureFlags == other.featureFlags &&
            resultLimit == other.resultLimit

    override fun hashCode(): Int =
        listOf(tenantId, locale, mode, featureFlags, resultLimit).hashCode()

    companion object {
        private const val serialVersionUID: Long = 1L
        private val featureFlagPattern = Regex("[a-z][a-z0-9-]{1,31}")

        operator fun invoke(
            tenantId: String,
            locale: Locale,
            mode: SearchMode,
            featureFlags: Set<String>,
            resultLimit: Int,
        ): SearchSettings {
            val normalizedTenantId = tenantId.trim()
            val normalizedFlags = featureFlags.map { it.trim() }.toSet()

            normalizedTenantId.requireNotBlank("tenantId")
            normalizedTenantId.length.requireInRange(1, 64, "tenantId.length")
            resultLimit.requireInRange(1, 20, "resultLimit")
            normalizedFlags.size.requireInRange(0, 8, "featureFlags.size")
            normalizedFlags.count { !it.matches(featureFlagPattern) }
                .requireInRange(0, 0, "featureFlags.invalid.size")

            return SearchSettings(
                tenantId = normalizedTenantId,
                locale = locale,
                mode = mode,
                featureFlags = Collections.unmodifiableSet(normalizedFlags),
                resultLimit = resultLimit,
            )
        }
    }
}

/**
 * Search request combining normalized query text with the latest settings.
 */
data class SearchRequest(
    val query: SearchQuery,
    val settings: SearchSettings,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Ranked hit returned by the fake search adapter.
 */
data class SearchHit(
    val id: String,
    val title: String,
    val score: Int,
): Serializable {

    override fun toString(): String = "SearchHit(id=$id, title=<redacted>, score=$score)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Search response emitted by [SearchPipeline].
 */
data class SearchResult(
    val request: SearchRequest,
    val hits: List<SearchHit>,
    val source: String,
): Serializable {

    override fun toString(): String =
        "SearchResult(query=<redacted>, tenant=<redacted>, hits=${hits.size}, source=<redacted>)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
