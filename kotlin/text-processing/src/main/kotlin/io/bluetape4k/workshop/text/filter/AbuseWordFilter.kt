package io.bluetape4k.workshop.text.filter

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.text.search.AhoCorasickMatch
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.SearchOptions
import io.bluetape4k.text.search.ahoCorasick

/**
 * Thread-safe abuse-word filter backed by an AhoCorasick automaton.
 *
 * All registered words are matched case-insensitively and under NFC normalization
 * so that variations of the same Unicode sequence are unified before comparison.
 * Overlapping matches are allowed to surface all violations in a single pass.
 *
 * ## Behavior / Contract
 * - Construction is O(total keyword length); each subsequent call to [containsAbuse],
 *   [filterText], or [findMatches] is O(text length + number of matches).
 * - [filterText] replaces each matched span with a string of `*` characters equal in
 *   length to the match span, preserving non-abusive text unchanged.
 * - Returns an empty list / unchanged text when [abuseWords] is empty.
 *
 * ```kotlin
 * val filter = AbuseWordFilter(listOf("badword", "spam"))
 * filter.containsAbuse("This contains badword!")  // true
 * filter.filterText("No spam allowed")            // "No **** allowed"
 * ```
 *
 * @param abuseWords collection of words to flag as abusive
 */
class AbuseWordFilter(abuseWords: Collection<String>) {

    companion object : KLogging()

    private val automaton: AhoCorasickAutomaton<String> = ahoCorasick {
        ignoreCase = true
        allowOverlaps = true
        normalization = NormalizationForm.NFC
        abuseWords.filter { it.isNotBlank() }.forEach { word -> keyword(word, word) }
    }

    /**
     * Returns `true` if [text] contains at least one registered abuse word.
     *
     * Uses the automaton's built-in early-exit path for efficiency.
     *
     * @param text input text to inspect
     */
    fun containsAbuse(text: String): Boolean {
        val result = automaton.containsMatch(text)
        log.debug { "containsAbuse length=${text.length} result=$result" }
        return result
    }

    /**
     * Replaces every matched abuse word in [text] with `*` characters and returns the result.
     *
     * Each matched span is replaced by a string of `*` of equal byte length, so the
     * returned string has the same length as [text].
     *
     * @param text input text to filter
     * @return text with all abuse-word occurrences masked
     */
    fun filterText(text: String): String {
        val filtered = automaton.replaceAll(text) { match ->
            "*".repeat(match.length)
        }
        log.debug { "filterText length=${text.length} -> filteredLength=${filtered.length}" }
        return filtered
    }

    /**
     * Returns all [AhoCorasickMatch] objects found in [text].
     *
     * Results are ordered by start position ascending. Overlapping matches are all included.
     *
     * @param text input text to search
     */
    fun findMatches(text: String): List<AhoCorasickMatch<String>> {
        val matches = automaton.parseText(text)
        log.debug { "findMatches length=${text.length} matches=${matches.size}" }
        return matches
    }
}
