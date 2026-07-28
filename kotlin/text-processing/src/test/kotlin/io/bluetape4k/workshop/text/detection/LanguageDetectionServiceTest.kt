package io.bluetape4k.workshop.text.detection

import com.github.pemistahl.lingua.api.Language
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LanguageDetectionServiceTest {

    companion object : KLogging()

    // shared detector 는 생성 비용이 크므로 이 class 의 모든 test 에서 재사용합니다.
    private val service = LanguageDetectionService()

    @Test
    fun `detects English text`() {
        val lang = service.detectLanguage("The quick brown fox jumps over the lazy dog.")
        lang shouldBeEqualTo Language.ENGLISH
    }

    @Test
    fun `detects Korean text`() {
        val lang = service.detectLanguage("안녕하세요. 오늘 날씨가 참 좋네요.")
        lang shouldBeEqualTo Language.KOREAN
    }

    @Test
    fun `detects Japanese text`() {
        val lang = service.detectLanguage("東京は日本の首都です。")
        lang shouldBeEqualTo Language.JAPANESE
    }

    @Test
    fun `returns null for blank input`() {
        service.detectLanguage("").shouldBeNull()
        service.detectLanguage("   ").shouldBeNull()
    }

    @Test
    fun `computeConfidenceValues returns non-empty map for real text`() {
        val values = service.computeConfidenceValues("Hello world, this is a test sentence.")
        values.shouldNotBeEmpty()
    }

    @Test
    fun `computeConfidenceValues returns empty map for blank input`() {
        val values = service.computeConfidenceValues("")
        values.shouldNotBeNull()
        values.isEmpty() shouldBeEqualTo true
    }

    @Test
    fun `top confidence language matches detectLanguage for English`() {
        val text = "This is a well-formed English sentence with enough words for detection."
        val detected = service.detectLanguage(text)
        val topConfidence = service.computeConfidenceValues(text).keys.firstOrNull()
        detected shouldBeEqualTo topConfidence
    }
}
