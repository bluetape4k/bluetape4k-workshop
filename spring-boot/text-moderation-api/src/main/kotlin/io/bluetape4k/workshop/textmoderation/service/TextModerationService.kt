package io.bluetape4k.workshop.textmoderation.service

import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.workshop.textmoderation.config.TextModerationProperties
import io.bluetape4k.workshop.textmoderation.model.ModerationResponse
import org.springframework.stereotype.Service

/**
 * Coordinates request validation, language detection, and blockword masking.
 */
@Service
class TextModerationService(
    private val properties: TextModerationProperties,
    private val languageDetector: LanguageDetector,
    private val moderationAutomaton: AhoCorasickAutomaton<String>,
) {

    companion object : KLogging() {
        private const val ABUSE_WORD_MATCHED = "ABUSE_WORD_MATCHED"
        private const val LANGUAGE_UNDETERMINED = "LANGUAGE_UNDETERMINED"
    }

    /**
     * Analyzes [text] and returns a normalized moderation response.
     *
     * @throws IllegalArgumentException when [text] is blank
     * @throws PayloadTooLargeException when [text] exceeds the configured size limit
     */
    fun analyze(text: String): ModerationResponse {
        validate(text)

        val detectedLanguage = detectLanguage(text)
        val confidenceValues = languageDetector.computeLanguageConfidenceValues(text)
        val matches = moderationAutomaton.parseText(text)
        val matchedTerms = matches.map { it.keyword }.distinct()
        val maskedText = moderationAutomaton.replaceAll(text) { match ->
            "*".repeat(match.length)
        }
        val warnings = buildList {
            if (matchedTerms.isNotEmpty()) add(ABUSE_WORD_MATCHED)
            if (detectedLanguage == null) add(LANGUAGE_UNDETERMINED)
        }

        log.debug {
            "analyze length=${text.length}, language=$detectedLanguage, matches=${matchedTerms.size}, warnings=$warnings"
        }

        return ModerationResponse(
            detectedLanguage = detectedLanguage?.name,
            confidence = detectedLanguage?.let { confidenceValues[it] },
            matchedTerms = matchedTerms,
            maskedText = maskedText,
            warnings = warnings,
        )
    }

    private fun validate(text: String) {
        if (text.isBlank()) {
            throw IllegalArgumentException("text must not be blank")
        }
        if (text.length > properties.maxTextCharacters) {
            throw PayloadTooLargeException("text exceeds ${properties.maxTextCharacters} characters")
        }
    }

    private fun detectLanguage(text: String): Language? {
        val detected = languageDetector.detectLanguageOf(text)
        return if (detected == Language.UNKNOWN) null else detected
    }
}
