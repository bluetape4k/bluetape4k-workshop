package io.bluetape4k.workshop.text.normalize

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug

/**
 * Lightweight, stateless text normalizer for pre-processing text before indexing or search.
 *
 * All operations return a new string and never mutate the input. This object is safe to call
 * from multiple threads concurrently.
 *
 * ## Behavior / Contract
 * - [normalize] lower-cases the text and collapses runs of whitespace to a single space,
 *   then trims leading and trailing whitespace.
 * - [extractKeywords] tokenizes on whitespace after normalization and removes tokens shorter
 *   than [minKeywordLength], returning a deduplicated list in encounter order.
 * - Blank input always returns an empty string / empty list.
 *
 * ```kotlin
 * TextNormalizer.normalize("  Hello   World  ")  // "hello world"
 * TextNormalizer.extractKeywords("the quick brown fox") // ["quick", "brown"]
 * ```
 */
object TextNormalizer : KLogging() {

    private val whitespaceRegex = Regex("\\s+")

    /**
     * Normalizes [text] by lower-casing and collapsing consecutive whitespace.
     *
     * @param text input string to normalize
     * @return normalized string, or an empty string when [text] is blank
     */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val normalized = whitespaceRegex.replace(text.lowercase().trim(), " ")
        log.debug { "normalize length=${text.length} -> normalizedLength=${normalized.length}" }
        return normalized
    }

    /**
     * Extracts deduplicated keywords from [text] after normalization.
     *
     * Tokens shorter than [minKeywordLength] are discarded. The returned list preserves
     * first-encounter order and contains no duplicates.
     *
     * @param text input string to tokenize
     * @param minKeywordLength minimum token length to include (default: 2)
     * @return ordered, deduplicated list of keywords
     */
    fun extractKeywords(text: String, minKeywordLength: Int = 2): List<String> {
        if (text.isBlank()) return emptyList()
        val keywords = normalize(text)
            .split(" ")
            .filter { it.length >= minKeywordLength }
            .distinct()
        log.debug { "extractKeywords length=${text.length} -> keywords=${keywords.size}" }
        return keywords
    }
}
