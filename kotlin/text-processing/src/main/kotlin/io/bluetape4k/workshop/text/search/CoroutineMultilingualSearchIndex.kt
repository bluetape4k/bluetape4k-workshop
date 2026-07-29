package io.bluetape4k.workshop.text.search

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.text.detection.CoroutineLanguageDetectionService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * coroutine-safe in-memory multilingual search index 입니다.
 *
 * ## Behavior / Contract
 * - 기존 synchronous [MultilingualSearchIndex] API 는 변경하지 않습니다.
 * - immutable index snapshot 을 만들기 때문에 concurrent search 가 index state 를 mutate 할 수 없습니다.
 * - [CoroutineLanguageDetectionService] 로 shared Lingua detector 접근을 직렬화합니다.
 * - CPU-bound detection, tokenization, candidate lookup, highlighting 은 [dispatcher] 에서 실행합니다.
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
     * coroutine 에서 index 를 검색하고 highlighted source text 를 포함한 ranked hit 를 반환합니다.
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
         * coroutine-safe [CoroutineMultilingualSearchIndex] 를 생성합니다.
         *
         * 여러 index 가 같은 detector 를 재사용하면서 detector 접근은 직렬화해야 할 때 하나의 shared [detectionService] 를 전달합니다.
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
