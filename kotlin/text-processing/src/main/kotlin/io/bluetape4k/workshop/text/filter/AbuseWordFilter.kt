package io.bluetape4k.workshop.text.filter

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.text.search.AhoCorasickMatch
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.SearchOptions
import io.bluetape4k.text.search.ahoCorasick

/**
 * AhoCorasick automaton 을 기반으로 하는 thread-safe abuse-word filter 입니다.
 *
 * 등록된 모든 단어는 case-insensitive 방식과 선택한 [normalization] 아래에서 matching 됩니다.
 * 기본 NFC는 기존 동작을 유지하고 NFKC는 compatibility 문자를 원문 offset으로 복원합니다.
 * overlapping match 를 허용해 한 번의 pass 에서 모든 위반을 드러냅니다.
 *
 * ## Behavior / Contract
 * - construction 비용은 O(total keyword length) 입니다. 이후 [containsAbuse], [filterText], [findMatches] 호출은 O(text length + number of matches) 입니다.
 * - [filterText] 는 matched span 마다 span 길이와 같은 개수의 `*` 문자열로 바꾸며, abuse 가 아닌 text 는 그대로 보존합니다.
 * - [abuseWords] 가 empty 이면 empty list 또는 변경되지 않은 text 를 반환합니다.
 *
 * ```kotlin
 * val filter = AbuseWordFilter(listOf("badword", "spam"))
 * filter.containsAbuse("This contains badword!")  // true
 * filter.filterText("No spam allowed")            // "No **** allowed"
 * ```
 *
 * @param abuseWords abuse 로 표시할 단어 collection 입니다.
 * @param normalization keyword와 입력에 적용할 Unicode normalization 형식입니다.
 */
class AbuseWordFilter @JvmOverloads constructor(
    abuseWords: Collection<String>,
    private val normalization: NormalizationForm = NormalizationForm.NFC,
) {

    companion object : KLogging()

    private val automaton: AhoCorasickAutomaton<String> = ahoCorasick {
        ignoreCase = true
        allowOverlaps = true
        normalization = this@AbuseWordFilter.normalization
        abuseWords.filter { it.isNotBlank() }.forEach { word -> keyword(word, word) }
    }

    /**
     * [text] 가 등록된 abuse word 를 하나 이상 포함하면 `true` 를 반환합니다.
     *
     * 효율을 위해 automaton 의 built-in early-exit path 를 사용합니다.
     *
     * @param text 검사할 입력 text 입니다.
     */
    fun containsAbuse(text: String): Boolean {
        val result = automaton.containsMatch(text)
        log.debug { "containsAbuse length=${text.length} result=$result" }
        return result
    }

    /**
     * [text] 안에서 match 된 모든 abuse word 를 `*` 문자로 바꾼 결과를 반환합니다.
     *
     * 각 matched 원문 span 은 같은 code-unit length의 `*` 문자열로 대체되므로 반환 문자열은 [text] 와 같은 길이를 가집니다.
     *
     * @param text filter 할 입력 text 입니다.
     * @return 모든 abuse-word occurrence 가 masking 된 text 입니다.
     */
    fun filterText(text: String): String {
        val filtered = automaton.replaceAll(text) { match ->
            "*".repeat(match.length)
        }
        log.debug { "filterText length=${text.length} -> filteredLength=${filtered.length}" }
        return filtered
    }

    /**
     * [text] 에서 찾은 모든 [AhoCorasickMatch] object 를 반환합니다.
     *
     * 결과는 start position ascending 순서입니다. overlapping match 도 모두 포함합니다.
     *
     * @param text 검색할 입력 text 입니다.
     */
    fun findMatches(text: String): List<AhoCorasickMatch<String>> {
        val matches = automaton.parseText(text)
        log.debug { "findMatches length=${text.length} matches=${matches.size}" }
        return matches
    }
}
