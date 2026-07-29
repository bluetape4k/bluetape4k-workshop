package io.bluetape4k.workshop.flow.search.pipeline

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.util.Collections
import java.util.Locale

/**
 * in-memory autocomplete catalog 에서 사용하는 search matching mode 입니다.
 */
enum class SearchMode {
    PREFIX,
    FUZZY,
    EXACT,
}

/**
 * normalization 된 non-blank search text 입니다.
 *
 * public factory 는 caller input 을 trim 하고 canonical value 를 검증한 뒤 normalized text 만 저장합니다. 생성된 `copy` 가 validation 을 우회하지 못하도록 이 class 는 의도적으로 data class 가 아닙니다.
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
 * request 시작 시점에 캡처한 search setting 입니다.
 *
 * `featureFlags` 는 unmodifiable set 으로 defensive copy 되어 이후 caller mutation 이 matching 이나 logging 동작을 바꿀 수 없습니다.
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
 * normalized query text 와 latest setting 을 결합한 search request 입니다.
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
 * fake search adapter 가 반환하는 ranked hit 입니다.
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
 * [SearchPipeline] 이 방출하는 search response 입니다.
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
