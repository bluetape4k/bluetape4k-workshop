package io.bluetape4k.workshop.text.detection

import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetector
import io.bluetape4k.lingua.allLanguageDetector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug

/**
 * Language detection service backed by the Lingua library.
 *
 * Wraps a shared [LanguageDetector] instance that is built once and reused across calls,
 * because model construction is expensive.
 *
 * ## Behavior / Contract
 * - [detectLanguage] returns `null` when the input is blank or the detector is uncertain
 *   (i.e., the best guess is [Language.UNKNOWN]).
 * - [computeConfidenceValues] returns a map sorted by confidence descending; empty on blank input.
 * - Reuse one [LanguageDetectionService] instance across all calls in an application to avoid
 *   re-loading language models on every detection.
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
     * Detects the most likely language for the given [text].
     *
     * Returns `null` when [text] is blank or when the detector cannot determine the language
     * (i.e., the result is [Language.UNKNOWN]).
     *
     * @param text input text to classify
     * @return detected [Language], or `null` if undetermined
     */
    fun detectLanguage(text: String): Language? {
        if (text.isBlank()) return null
        val detected = detector.detectLanguageOf(text)
        log.debug { "detectLanguage text='${text.take(40)}' -> $detected" }
        return if (detected == Language.UNKNOWN) null else detected
    }

    /**
     * Returns a map of [Language] to confidence values for all languages that the detector
     * considers plausible for [text], sorted by confidence descending.
     *
     * The map is empty when [text] is blank.
     *
     * @param text input text to classify
     * @return language-to-confidence map sorted by confidence descending
     */
    fun computeConfidenceValues(text: String): Map<Language, Double> {
        if (text.isBlank()) return emptyMap()
        val values = detector.computeLanguageConfidenceValues(text)
        log.debug { "computeConfidenceValues text='${text.take(40)}' -> top=${values.entries.firstOrNull()}" }
        return values.entries
            .sortedByDescending { it.value }
            .associate { it.key to it.value }
    }
}
