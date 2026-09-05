package io.bluetape4k.workshop.textmoderation.service

import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.tokenizer.utils.DictionaryVersion
import io.bluetape4k.workshop.textmoderation.config.TextModerationProperties
import io.bluetape4k.workshop.textmoderation.model.ModerationResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * request validation, language detection, blockword masking 을 조율합니다.
 */
@Service
class TextModerationService @Autowired constructor(
    private val properties: TextModerationProperties,
    private val languageDetector: LanguageDetector,
    private val moderationDictionary: VersionedModerationDictionary,
) {

    /** 기존 direct-construction caller가 automaton을 그대로 전달할 수 있는 호환 constructor입니다. */
    constructor(
        properties: TextModerationProperties,
        languageDetector: LanguageDetector,
        moderationAutomaton: AhoCorasickAutomaton<String>,
    ) : this(
        properties = properties,
        languageDetector = languageDetector,
        moderationDictionary = VersionedModerationDictionary.fromAutomaton(moderationAutomaton),
    )

    companion object : KLogging() {
        private const val ABUSE_WORD_MATCHED = "ABUSE_WORD_MATCHED"
        private const val LANGUAGE_UNDETERMINED = "LANGUAGE_UNDETERMINED"
    }

    /**
     * [text] 를 분석하고 normalized moderation response 를 반환합니다.
     *
     * @throws IllegalArgumentException [text] 가 비어 있을 때 발생합니다.
     * @throws PayloadTooLargeException [text] 가 설정된 size limit 을 넘을 때 발생합니다.
     */
    fun analyze(text: String): ModerationResponse = analyzeWithVersion(text).response

    /** 요청 시작 시 dictionary snapshot을 한 번 캡처하고 version metadata와 함께 반환합니다. */
    fun analyzeWithVersion(text: String): VersionedModerationResult {
        validate(text)

        val dictionary = moderationDictionary.snapshot()
        val detectedLanguage = detectLanguage(text)
        val confidenceValues = languageDetector.computeLanguageConfidenceValues(text)
        val matches = dictionary.value.automaton.parseText(text)
        val matchedTerms = matches.map { it.keyword }.distinct()
        val maskedText = dictionary.value.automaton.replaceAll(text) { match ->
            "*".repeat(match.length)
        }
        val warnings = buildList {
            if (matchedTerms.isNotEmpty()) add(ABUSE_WORD_MATCHED)
            if (detectedLanguage == null) add(LANGUAGE_UNDETERMINED)
        }

        log.debug {
            "analyze length=${text.length}, language=$detectedLanguage, matches=${matchedTerms.size}, " +
                    "warnings=$warnings, dictionary=${dictionary.version.name}, revision=${dictionary.version.revision}"
        }

        return VersionedModerationResult(
            dictionary = ModerationDictionaryMetadata(
                version = dictionary.version,
                wordCount = dictionary.value.wordCount,
                totalCharacters = dictionary.value.totalCharacters,
            ),
            response = ModerationResponse(
                detectedLanguage = detectedLanguage?.name,
                confidence = detectedLanguage?.let { confidenceValues[it] },
                matchedTerms = matchedTerms,
                maskedText = maskedText,
                warnings = warnings,
            ),
        )
    }

    /** 새 blockword candidate를 준비해 더 높은 revision으로 원자 교체합니다. */
    fun reloadDictionary(
        version: DictionaryVersion,
        loader: () -> Collection<String>,
    ): ModerationDictionaryMetadata = moderationDictionary.reload(version, loader)

    /** bounded history의 가장 최근 blockword revision으로 되돌아갑니다. */
    fun rollbackDictionary(): ModerationDictionaryMetadata = moderationDictionary.rollback()

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
