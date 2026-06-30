package io.bluetape4k.workshop.textmoderation.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.lingua.allLanguageDetector
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.ahoCorasick
import io.bluetape4k.workshop.textmoderation.config.TextModerationProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextModerationServiceTest {

    private val service = TextModerationService(
        properties = TextModerationProperties(maxTextCharacters = 120),
        languageDetector = allLanguageDetector {
            withMinimumRelativeDistance(0.0)
            withLowAccuracyMode()
        },
        moderationAutomaton = ahoCorasick {
            ignoreCase = true
            allowOverlaps = true
            normalization = NormalizationForm.NFC
            listOf("spam", "badword", "abuse", "hate").forEach { keyword(it, it) }
        },
    )

    @Test
    fun `analyze returns masked text and language for valid English text`() {
        val response = service.analyze("Please remove spam from this English sentence.")

        response.detectedLanguage shouldBeEqualTo "ENGLISH"
        response.confidence.shouldNotBeNull() shouldBeGreaterThan 0.0
        response.matchedTerms shouldBeEqualTo listOf("spam")
        response.maskedText shouldBeEqualTo "Please remove **** from this English sentence."
        response.warnings shouldContain "ABUSE_WORD_MATCHED"
    }

    @Test
    fun `analyze detects multilingual examples deterministically`() {
        val korean = service.analyze("안녕하세요. 오늘 날씨가 좋고 깨끗한 문장입니다.")
        val japanese = service.analyze("東京は日本の首都です。これは安全な文章です。")

        korean.detectedLanguage shouldBeEqualTo "KOREAN"
        japanese.detectedLanguage shouldBeEqualTo "JAPANESE"
    }

    @Test
    fun `blank text is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            service.analyze("   ")
        }
    }

    @Test
    fun `oversized text is rejected separately`() {
        assertFailsWith<PayloadTooLargeException> {
            service.analyze("x".repeat(121))
        }
    }
}
