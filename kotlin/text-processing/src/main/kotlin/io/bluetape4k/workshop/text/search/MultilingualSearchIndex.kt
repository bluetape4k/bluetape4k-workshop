package io.bluetape4k.workshop.text.search

import com.github.pemistahl.lingua.api.Language
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.text.search.AhoCorasickMatch
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.SearchToken
import io.bluetape4k.text.search.ahoCorasick
import io.bluetape4k.tokenizer.japanese.JapaneseProcessor
import io.bluetape4k.tokenizer.korean.KoreanProcessor
import io.bluetape4k.workshop.text.detection.LanguageDetectionService
import io.bluetape4k.workshop.text.normalize.TextNormalizer
import java.io.Serializable

/**
 * [MultilingualSearchIndex] 가 받는 immutable search document 입니다.
 *
 * factory 는 document 를 indexing 하기 전에 caller input 을 trim 하고 blank field 를 거부합니다.
 */
class SearchDocument private constructor(
    val id: String,
    val title: String,
    val text: String,
): Serializable {

    override fun toString(): String =
        "SearchDocument(id=$id, title=<redacted>, length=${text.length})"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SearchDocument &&
            id == other.id &&
            title == other.title &&
            text == other.text

    override fun hashCode(): Int = listOf(id, title, text).hashCode()

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * caller 가 제공한 field 를 trim 한 뒤 검증된 [SearchDocument] 를 생성합니다.
         */
        fun of(
            id: String,
            title: String,
            text: String,
        ): SearchDocument = invoke(id, title, text)

        /**
         * caller 가 제공한 field 를 trim 한 뒤 검증된 [SearchDocument] 를 생성합니다.
         */
        operator fun invoke(
            id: String,
            title: String,
            text: String,
        ): SearchDocument {
            val normalizedId = id.trim()
            val normalizedTitle = title.trim()
            val normalizedText = text.trim()

            normalizedId.requireNotBlank("id")
            normalizedTitle.requireNotBlank("title")
            normalizedText.requireNotBlank("text")

            return SearchDocument(
                id = normalizedId,
                title = normalizedTitle,
                text = normalizedText,
            )
        }
    }
}

/**
 * index 가 사용하는 language 와 token set 으로 materialize 된 document 입니다.
 */
data class IndexedDocument(
    val document: SearchDocument,
    val language: Language?,
    val terms: Set<String>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * hit 와 함께 반환되는 search match span 입니다.
 */
data class SearchHighlightMatch(
    val term: String,
    val text: String,
    val start: Int,
    val end: Int,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * [MultilingualSearchIndex.search] 가 반환하는 ranked hit 입니다.
 */
data class SearchHighlightHit(
    val document: SearchDocument,
    val language: Language?,
    val score: Int,
    val matches: List<SearchHighlightMatch>,
    val highlightedText: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Lingua, Korean/Japanese tokenizer, English normalization, Aho-Corasick highlighting 을 기반으로 하는 in-memory multilingual search index 입니다.
 *
 * ## Behavior / Contract
 * - document 는 index construction 시점에 한 번 language detection 되고 detected language 기준으로 tokenize 됩니다.
 * - Korean text 는 [KoreanProcessor] normalization 과 noun-oriented tokenization 을 사용합니다.
 * - Japanese text 는 [JapaneseProcessor] noun filtering 을 사용합니다.
 * - English 와 unknown text 는 [TextNormalizer] keyword extraction 을 사용합니다.
 * - search 는 먼저 inverted index 로 candidate 를 좁힌 뒤 Aho-Corasick 으로 original-text match span 과 deterministic non-overlapping `<mark>` highlight 를 반환합니다.
 *
 * ```kotlin
 * val index = MultilingualSearchIndex.indexOf(
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
class MultilingualSearchIndex private constructor(
    val indexedDocuments: List<IndexedDocument>,
    private val detectionService: LanguageDetectionService,
): Serializable {

    private val documentsById: Map<String, IndexedDocument> =
        indexedDocuments.associateBy { it.document.id }

    private val invertedIndex: Map<String, Set<String>> =
        buildSearchInvertedIndex(indexedDocuments)

    /**
     * index 를 검색하고 source offset 과 highlighted text 를 포함한 ranked hit 를 반환합니다.
     *
     * [query] 가 blank 이거나 query term 을 추출할 수 없거나 어떤 indexed document 도 query term 을 포함하지 않으면 empty list 를 반환합니다.
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<SearchHighlightHit> {
        if (query.isBlank()) return emptyList()
        val queryTerms = tokenizeSearchText(query, detectionService.detectLanguage(query))
        if (queryTerms.isEmpty()) return emptyList()

        val candidateIds = queryTerms
            .flatMap { term -> invertedIndex[term].orEmpty() }
            .toCollection(linkedSetOf())

        if (candidateIds.isEmpty()) {
            log.debug { "search queryLength=${query.length} terms=${queryTerms.size} -> no candidates" }
            return emptyList()
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
        return hits
    }

    companion object: KLogging() {
        private const val serialVersionUID: Long = 1L
        private const val DEFAULT_LIMIT = 10

        /**
         * unique id 를 가진 non-empty [documents] 로 재사용 가능한 [MultilingualSearchIndex] 를 생성합니다.
         *
         * application 이 이미 [LanguageDetectionService] 를 소유하고 있으면 기존 instance 를 전달합니다. 이렇게 하면 index 마다 Lingua language model 을 다시 build 하지 않습니다.
         */
        fun indexOf(
            documents: Collection<SearchDocument>,
            detectionService: LanguageDetectionService = LanguageDetectionService(),
        ): MultilingualSearchIndex {
            validateSearchDocuments(documents)
            val indexed = documents.map { document ->
                val language = detectionService.detectLanguage(document.text)
                IndexedDocument(
                    document = document,
                    language = language,
                    terms = tokenizeSearchText(document.text, language).toCollection(linkedSetOf()),
                )
            }
            return MultilingualSearchIndex(indexed, detectionService)
        }
    }
}

internal fun validateSearchDocuments(documents: Collection<SearchDocument>) {
    documents.requireNotEmpty("documents")
    val ids = documents.map { it.id }
    (ids.size - ids.toSet().size)
        .requireZero("documents.duplicateIds", "documents must have unique ids.")
}

private fun Int.requireZero(parameterName: String, message: String): Int = apply {
    try {
        requireInRange(0, 0, parameterName)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException(message, e)
    }
}

internal fun tokenizeSearchText(text: String, language: Language?): List<String> =
    when (language) {
        Language.KOREAN -> tokenizeKorean(text)
        Language.JAPANESE -> tokenizeJapanese(text)
        else -> TextNormalizer.extractKeywords(text)
    }
        .mapNotNull { it.toIndexTermOrNull() }
        .distinct()

internal fun buildSearchInvertedIndex(indexedDocuments: List<IndexedDocument>): Map<String, Set<String>> =
    indexedDocuments
        .flatMap { indexed -> indexed.terms.map { term -> term to indexed.document.id } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ids) -> ids.toCollection(linkedSetOf()) }

internal fun buildSearchHighlighter(queryTerms: Collection<String>): AhoCorasickAutomaton<String> =
    ahoCorasick {
        ignoreCase = true
        allowOverlaps = true
        normalization = NormalizationForm.NFC
        queryTerms.forEach { term -> keyword(term, term) }
    }

private fun tokenizeKorean(text: String): List<String> {
    val normalized = KoreanProcessor.normalize(text).toString()
    return KoreanProcessor.tokenizeForNoun(normalized).map { it.text }
}

private fun tokenizeJapanese(text: String): List<String> =
    JapaneseProcessor
        .filterNoun(JapaneseProcessor.tokenize(text))
        .map { it.surface }

private fun String.toIndexTermOrNull(): String? {
    val normalized = TextNormalizer.normalize(this)
        .trim { !it.isLetterOrDigit() }
    return normalized.takeIf { it.length >= 2 }
}

internal fun List<AhoCorasickMatch<String>>.toHighlightMatches(text: String): List<SearchHighlightMatch> =
    map { match ->
        SearchHighlightMatch(
            term = match.value,
            text = text.substring(match.start, match.end + 1),
            start = match.start,
            end = match.end,
        )
    }

internal fun AhoCorasickAutomaton<String>.toHighlightedText(text: String): String =
    tokenize(text).joinToString(separator = "") { token ->
        when (token) {
            is SearchToken.Fragment -> token.text
            is SearchToken.Match -> "<mark>${token.text}</mark>"
        }
    }
