package io.bluetape4k.workshop.text.search

import com.github.pemistahl.lingua.api.Language
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
 * Immutable search document accepted by [MultilingualSearchIndex].
 *
 * The factory trims caller input and rejects blank fields before the document is indexed.
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
         * Creates a validated [SearchDocument] after trimming caller-provided fields.
         */
        fun of(
            id: String,
            title: String,
            text: String,
        ): SearchDocument = invoke(id, title, text)

        /**
         * Creates a validated [SearchDocument] after trimming caller-provided fields.
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
 * Document materialized into the language and token set used by the index.
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
 * Search match span returned with a hit.
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
 * Ranked hit returned by [MultilingualSearchIndex.search].
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
 * In-memory multilingual search index backed by Lingua, Korean/Japanese tokenizers,
 * English normalization, and Aho-Corasick highlighting.
 *
 * ## Behavior / Contract
 * - Documents are detected once at index construction time and tokenized by detected language.
 * - Korean text uses [KoreanProcessor] normalization plus noun-oriented tokenization.
 * - Japanese text uses [JapaneseProcessor] noun filtering.
 * - English and unknown text use [TextNormalizer] keyword extraction.
 * - Search first narrows candidates through an inverted index, then uses Aho-Corasick to
 *   return original-text match spans and deterministic non-overlapping `<mark>` highlights.
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
     * Searches the index and returns ranked hits with source offsets and highlighted text.
     *
     * Returns an empty list when [query] is blank, no query terms can be extracted, or no
     * indexed document contains any query term.
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<SearchHighlightHit> {
        if (query.isBlank()) return emptyList()
        val queryTerms = tokenizeSearchText(query, detectionService.detectLanguage(query))
        if (queryTerms.isEmpty()) return emptyList()

        val candidateIds = queryTerms
            .flatMap { term -> invertedIndex[term].orEmpty() }
            .toCollection(linkedSetOf())

        if (candidateIds.isEmpty()) {
            log.debug { "search query='${query.take(40)}' terms=$queryTerms -> no candidates" }
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

        log.debug { "search query='${query.take(40)}' terms=$queryTerms -> hits=${hits.size}" }
        return hits
    }

    companion object: KLogging() {
        private const val serialVersionUID: Long = 1L
        private const val DEFAULT_LIMIT = 10

        /**
         * Builds a reusable [MultilingualSearchIndex] from non-empty [documents] with unique ids.
         *
         * Pass an existing [LanguageDetectionService] when the application already owns one;
         * this avoids rebuilding Lingua language models for every index.
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
    require(ids.size == ids.toSet().size) {
        "documents must have unique ids."
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
