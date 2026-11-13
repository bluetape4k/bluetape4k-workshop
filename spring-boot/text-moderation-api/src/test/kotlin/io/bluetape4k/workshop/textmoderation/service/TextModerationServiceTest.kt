package io.bluetape4k.workshop.textmoderation.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.get
import io.bluetape4k.lingua.allLanguageDetector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.ahoCorasick
import io.bluetape4k.tokenizer.utils.DictionaryVersion
import io.bluetape4k.workshop.textmoderation.config.TextModerationProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextModerationServiceTest {

    companion object: KLogging()

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

        log.debug { "response=$response" }
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

        log.debug { "korean=$korean" }
        log.debug { "japanese=$japanese" }
        
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

    @Test
    fun `versioned analyze는 parse와 mask에 같은 snapshot을 사용한다`() {
        val dictionary = VersionedModerationDictionary(
            initialVersion = DictionaryVersion("moderation-blockwords", 1),
            initialWords = listOf("spam"),
            historyCapacity = 1,
        )
        val versionedService = TextModerationService(
            properties = TextModerationProperties(maxTextCharacters = 120),
            languageDetector = allLanguageDetector {
                withMinimumRelativeDistance(0.0)
                withLowAccuracyMode()
            },
            moderationDictionary = dictionary,
        )
        val executor = Executors.newFixedThreadPool(5)
        val start = CountDownLatch(1)

        try {
            val readers = List(4) {
                executor.submit<List<VersionedModerationResult>> {
                    start.await(5.seconds)
                    List(100) { versionedService.analyzeWithVersion("spam ham") }
                }
            }
            val writer = executor.submit {
                start.await(5.seconds)
                versionedService.reloadDictionary(DictionaryVersion("moderation-blockwords", 2)) {
                    listOf("ham")
                }
            }

            start.countDown()
            writer.get(5.seconds)

            readers.flatMap { it.get(5.seconds) }.forEach { result ->
                log.debug { "result=$result" }
                
                when (result.dictionary.version.revision) {
                    1L -> {
                        result.response.matchedTerms shouldBeEqualTo listOf("spam")
                        result.response.maskedText shouldBeEqualTo "**** ham"
                    }
                    2L -> {
                        result.response.matchedTerms shouldBeEqualTo listOf("ham")
                        result.response.maskedText shouldBeEqualTo "spam ***"
                    }
                    else -> error("unexpected revision=${result.dictionary.version.revision}")
                }
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5.seconds)
        }
    }

    @Test
    fun `기존 analyze와 automaton constructor는 호환된다`() {
        service.analyze("spam").maskedText shouldBeEqualTo "****"
    }
}
