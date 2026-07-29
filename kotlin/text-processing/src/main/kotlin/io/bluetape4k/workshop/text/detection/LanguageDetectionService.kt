package io.bluetape4k.workshop.text.detection

import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetector
import io.bluetape4k.lingua.allLanguageDetector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug

/**
 * Lingua library 를 기반으로 하는 language detection service 입니다.
 *
 * model construction 비용이 크므로 한 번 만든 shared [LanguageDetector] instance 를 감싸 여러 호출에서 재사용합니다.
 *
 * ## Behavior / Contract
 * - input 이 blank 이거나 detector 가 확신하지 못하면 [detectLanguage] 는 `null` 을 반환합니다. 즉 최선의 추정값이 [Language.UNKNOWN] 인 경우입니다.
 * - [computeConfidenceValues] 는 confidence descending 순서의 map 을 반환하며, blank input 에서는 empty map 을 반환합니다.
 * - detection 마다 language model 을 다시 load 하지 않도록 application 안에서는 하나의 [LanguageDetectionService] instance 를 재사용합니다.
 *
 * ```kotlin
 * val service = LanguageDetectionService()
 * service.detectLanguage("Hello, World!")        // Language.ENGLISH
 * service.detectLanguage("안녕하세요.")           // Language.KOREAN
 * ```
 */
class LanguageDetectionService {

    companion object : KLogging()

    private val detector: LanguageDetector = allLanguageDetector {
        withMinimumRelativeDistance(0.0)
        withLowAccuracyMode()
    }

    /**
     * 주어진 [text] 에 가장 가능성이 높은 언어를 감지합니다.
     *
     * [text] 가 blank 이거나 detector 가 언어를 결정할 수 없으면, 즉 결과가 [Language.UNKNOWN] 이면 `null` 을 반환합니다.
     *
     * @param text 분류할 입력 text 입니다.
     * @return 감지된 [Language] 입니다. 결정할 수 없으면 `null` 입니다.
     */
    fun detectLanguage(text: String): Language? {
        if (text.isBlank()) return null
        val detected = detector.detectLanguageOf(text)
        log.debug { "detectLanguage length=${text.length} -> $detected" }
        return if (detected == Language.UNKNOWN) null else detected
    }

    /**
     * detector 가 [text] 에 대해 가능성이 있다고 판단한 모든 언어의 [Language] to confidence value map 을 confidence descending 순서로 반환합니다.
     *
     * [text] 가 blank 이면 empty map 을 반환합니다.
     *
     * @param text 분류할 입력 text 입니다.
     * @return confidence descending 순서로 정렬된 language-to-confidence map 입니다.
     */
    fun computeConfidenceValues(text: String): Map<Language, Double> {
        if (text.isBlank()) return emptyMap()
        val values = detector.computeLanguageConfidenceValues(text)
        log.debug { "computeConfidenceValues length=${text.length} -> top=${values.entries.firstOrNull()?.key}" }
        return values.entries
            .sortedByDescending { it.value }
            .associate { it.key to it.value }
    }
}
