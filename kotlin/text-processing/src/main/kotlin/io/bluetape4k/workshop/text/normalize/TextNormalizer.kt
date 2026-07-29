package io.bluetape4k.workshop.text.normalize

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug

/**
 * indexing 또는 search 전에 text 를 전처리하는 lightweight, stateless normalizer 입니다.
 *
 * 모든 operation 은 새 string 을 반환하고 input 을 mutate 하지 않습니다. 이 object 는 여러 thread 에서 동시에 호출해도 안전합니다.
 *
 * ## Behavior / Contract
 * - [normalize] 는 text 를 lowercase 로 바꾸고 연속 whitespace 를 single space 로 접은 뒤 leading/trailing whitespace 를 trim 합니다.
 * - [extractKeywords] 는 normalization 이후 whitespace 기준으로 tokenize 하고 [minKeywordLength] 보다 짧은 token 을 제거한 뒤, encounter order 를 보존하는 deduplicated list 를 반환합니다.
 * - blank input 은 항상 empty string 또는 empty list 를 반환합니다.
 *
 * ```kotlin
 * TextNormalizer.normalize("  Hello   World  ")  // "hello world"
 * TextNormalizer.extractKeywords("the quick brown fox") // ["quick", "brown"]
 * ```
 */
object TextNormalizer : KLogging() {

    private val whitespaceRegex = Regex("\\s+")

    /**
     * [text] 를 lowercase 로 바꾸고 연속 whitespace 를 접어 normalize 합니다.
     *
     * @param text normalize 할 입력 string 입니다.
     * @return normalized string 입니다. [text] 가 blank 이면 empty string 입니다.
     */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val normalized = whitespaceRegex.replace(text.lowercase().trim(), " ")
        log.debug { "normalize length=${text.length} -> normalizedLength=${normalized.length}" }
        return normalized
    }

    /**
     * normalization 이후 [text] 에서 deduplicated keyword 를 추출합니다.
     *
     * [minKeywordLength] 보다 짧은 token 은 버립니다. 반환 list 는 first-encounter order 를 보존하고 duplicate 를 포함하지 않습니다.
     *
     * @param text tokenize 할 입력 string 입니다.
     * @param minKeywordLength 포함할 minimum token length 입니다. 기본값은 2입니다.
     * @return 순서가 보존된 deduplicated keyword list 입니다.
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
