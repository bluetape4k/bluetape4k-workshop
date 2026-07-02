package io.bluetape4k.workshop.text.search

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.text.detection.CoroutineLanguageDetectionService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Coroutine-safe in-memory multilingual search index.
 *
 * ## Behavior / Contract
 * - Keeps the existing synchronous [MultilingualSearchIndex] API unchanged.
 * - Builds immutable index snapshots, so concurrent searches cannot mutate index state.
 * - Uses [CoroutineLanguageDetectionService] to serialize access to the shared Lingua detector.
 * - Runs CPU-bound detection, tokenization, candidate lookup, and highlighting on [dispatcher].
 *
 * ```kotlin
 * val index = CoroutineMultilingualSearchIndex.indexOf(
 *     listOf(
 *         SearchDocument.of(
 *             id = "ko-1",
 *             title = "Korean cafe",
 *             text = "서울 카페 예약",
 *         )
 *     )
 * )
 * val hits = index.search("서울 카페")
 * ```
 */
class CoroutineMultilingualSearchIndex private constructor(
    indexedDocuments: List<IndexedDocument>,
    private val detectionService: CoroutineLanguageDetectionService,
    private val dispatcher: CoroutineDispatcher,
) {

    val indexedDocuments: List<IndexedDocument> =
        immutableIndexedDocuments(indexedDocuments)

    private val documentsById: Map<String, IndexedDocument> =
        Collections.unmodifiableMap(this.indexedDocuments.associateBy { it.document.id })

    private val invertedIndex: Map<String, Set<String>> =
        immutableInvertedIndex(buildSearchInvertedIndex(this.indexedDocuments))

    /**
     * Searches the index from a coroutine and returns ranked hits with highlighted source text.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<SearchHighlightHit> =
        withContext(dispatcher) {
            if (query.isBlank()) return@withContext emptyList()

            val queryTerms = tokenizeSearchText(query, detectionService.detectLanguage(query))
            if (queryTerms.isEmpty()) return@withContext emptyList()

            val candidateIds = queryTerms
                .flatMap { term -> invertedIndex[term].orEmpty() }
                .toCollection(linkedSetOf())

            if (candidateIds.isEmpty()) {
                log.debug { "search queryLength=${query.length} terms=${queryTerms.size} -> no candidates" }
                return@withContext emptyList()
            }

            val automaton = buildSearchHighlighter(queryTerms)
            val hits = candidateIds
                .mapNotNull { id ->
                    val indexed = documentsById[id] ?: return@mapNotNull null
                    val matches = automaton.parseText(indexed.document.text)
                    if (matches.isEmpty()) {
                        return@mapNotNull null
                    }
                    SearchHighlightHit(
                        document = indexed.document,
                        language = indexed.language,
                        score = matches.map { it.value }.toSet().size,
                        matches = matches.toHighlightMatches(indexed.document.text),
                        highlightedText = automaton.toHighlightedText(indexed.document.text),
                    )
                }
                .sortedWith(
                    compareByDescending<SearchHighlightHit> { it.score }
                        .thenBy { it.document.id }
                )
                .take(limit.coerceAtLeast(0))

            log.debug { "search queryLength=${query.length} terms=${queryTerms.size} -> hits=${hits.size}" }
            hits
        }

    companion object: KLoggingChannel() {
        private const val DEFAULT_LIMIT = 10

        /**
         * Builds a coroutine-safe [CoroutineMultilingualSearchIndex].
         *
         * Pass one shared [detectionService] when several indexes should reuse the same detector
         * while still serializing detector access.
         */
        suspend fun indexOf(
            documents: Collection<SearchDocument>,
            detectionService: CoroutineLanguageDetectionService = CoroutineLanguageDetectionService(),
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): CoroutineMultilingualSearchIndex =
            withContext(dispatcher) {
                validateSearchDocuments(documents)
                val indexed = documents.map { document ->
                    val language = detectionService.detectLanguage(document.text)
                    IndexedDocument(
                        document = document,
                        language = language,
                        terms = tokenizeSearchText(document.text, language).toCollection(linkedSetOf()),
                    )
                }
                CoroutineMultilingualSearchIndex(
                    indexedDocuments = indexed,
                    detectionService = detectionService,
                    dispatcher = dispatcher,
                )
            }

        private fun immutableIndexedDocuments(indexedDocuments: List<IndexedDocument>): List<IndexedDocument> =
            Collections.unmodifiableList(
                indexedDocuments.map { indexed ->
                    indexed.copy(
                        terms = Collections.unmodifiableSet(indexed.terms.toCollection(linkedSetOf()))
                    )
                }
            )

        private fun immutableInvertedIndex(indexedDocuments: Map<String, Set<String>>): Map<String, Set<String>> =
            Collections.unmodifiableMap(
                indexedDocuments.mapValues { (_, ids) ->
                    Collections.unmodifiableSet(ids.toCollection(linkedSetOf()))
                }
            )
    }
}
