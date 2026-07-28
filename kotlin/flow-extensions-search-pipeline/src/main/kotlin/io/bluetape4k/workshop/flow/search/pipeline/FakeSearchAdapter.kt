package io.bluetape4k.workshop.flow.search.pipeline

import java.io.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * workshop README 와 test 에서 사용하는 deterministic in-memory adapter 입니다.
 *
 * 고정 catalog 와 literal string matching 만 사용합니다. caller input 으로 regex, script, SQL-like expression, reflection, dynamic query DSL 을 만들지 않습니다.
 */
class FakeSearchAdapter(
    private val latencyMillis: Long = 0L,
    private val catalog: List<CatalogEntry> = defaultCatalog,
    private val onStart: (SearchRequest) -> Unit = {},
    private val onCancel: (SearchRequest) -> Unit = {},
): SearchAdapter {

    override suspend fun search(request: SearchRequest): SearchResult {
        onStart(request)

        try {
            if (latencyMillis > 0) {
                delay(latencyMillis)
            }

            val hits = catalog.asSequence()
                .filter { it.matches(request) }
                .take(request.settings.resultLimit)
                .map { it.toHit(request) }
                .toList()

            return SearchResult(request, hits, "fake-catalog")
        } catch (e: CancellationException) {
            onCancel(request)
            throw e
        }
    }

    private fun CatalogEntry.matches(request: SearchRequest): Boolean {
        val title = title.lowercase(request.settings.locale)
        val query = request.query.text.lowercase(request.settings.locale)

        return when (request.settings.mode) {
            SearchMode.PREFIX -> title.startsWith(query)
            SearchMode.FUZZY -> title.contains(query)
            SearchMode.EXACT -> title == query
        }
    }

    private fun CatalogEntry.toHit(request: SearchRequest): SearchHit =
        SearchHit(
            id = id,
            title = title,
            score = baseScore + request.settings.featureFlags.size,
        )

    data class CatalogEntry(
        val id: String,
        val title: String,
        val baseScore: Int,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        val defaultCatalog: List<CatalogEntry> = listOf(
            CatalogEntry("redis", "redis", 100),
            CatalogEntry("redisson", "redisson", 90),
            CatalogEntry("resilience4j", "resilience4j", 80),
            CatalogEntry("reactor", "reactor", 70),
            CatalogEntry("retry", "retry", 60),
        )
    }
}
