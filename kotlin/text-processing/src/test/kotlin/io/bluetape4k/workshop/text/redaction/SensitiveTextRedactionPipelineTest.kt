package io.bluetape4k.workshop.text.redaction

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.coroutines.runSuspendDefault
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.workshop.text.detection.LanguageDetectionService
import io.bluetape4k.workshop.text.filter.AbuseWordFilter
import io.bluetape4k.workshop.text.normalize.TextNormalizer
import io.bluetape4k.workshop.text.search.CoroutineMultilingualSearchIndex
import io.bluetape4k.workshop.text.search.MultilingualSearchIndex
import io.bluetape4k.workshop.text.search.SearchDocument
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.text.Normalizer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.measureTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SensitiveTextRedactionPipelineTest {

    private val pipeline = SensitiveTextRedactionPipeline.default()

    private val email = "user@example.test"
    private val phone = "555-010-1234"
    private val token = "token=demo_token_value_123456"
    private val keyword = "account number"

    @Test
    fun `preserves the JVM three argument policy factory`() {
        val factory = SensitiveRedactionPolicy.Companion::class.java.getMethod(
            "of",
            Collection::class.java,
            Char::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val rules = listOf(SensitiveRedactionRule.keyword("keyword.safe", "keyword", "safe"))

        val policy = factory.invoke(SensitiveRedactionPolicy.Companion, rules, '#', 1_024)
            as SensitiveRedactionPolicy

        policy.maskChar shouldBeEqualTo '#'
        policy.maxTextLength shouldBeEqualTo 1_024
        policy.keywordNormalization shouldBeEqualTo NormalizationForm.NFC
    }

    @Test
    fun `redacts email phone token and configured keyword deterministically`() {
        val input = "Contact $email at $phone with $token for $keyword review."
        val expected = "Contact ${"*".repeat(email.length)} at ${"*".repeat(phone.length)} " +
            "with ${"*".repeat(token.length)} for ${"*".repeat(keyword.length)} review."

        val result = pipeline.redact(input)
        val repeated = pipeline.redact(input)

        result.redactedText.length shouldBeEqualTo input.length
        result.redactedText shouldBeEqualTo expected
        result.redactedText.startsWith("Contact ").shouldBeTrue()
        result.redactedText.endsWith(" review.").shouldBeTrue()
        repeated shouldBeEqualTo result
        result.redactedText shouldNotContain email
        result.redactedText shouldNotContain phone
        result.redactedText shouldNotContain token
        result.redactedText shouldNotContain keyword
        result.spans.map { it.range.startInclusive } shouldBeEqualTo result.spans.map { it.range.startInclusive }.sorted()
        result.spans.map { it.category } shouldBeEqualTo listOf("contact", "contact", "secret", "keyword")
        result.spans.map { it.ruleIds.single() } shouldBeEqualTo listOf("email", "phone", "token", "support-keyword")
        result.spans.map { it.matchedLength } shouldBeEqualTo
            listOf(email.length, phone.length, token.length, keyword.length)
        result.spans.map { it.range.startInclusive } shouldBeEqualTo listOf(
            input.indexOf(email),
            input.indexOf(phone),
            input.indexOf(token),
            input.indexOf(keyword),
        )
        result.spans.map { it.range.endExclusive } shouldBeEqualTo listOf(
            input.indexOf(email) + email.length,
            input.indexOf(phone) + phone.length,
            input.indexOf(token) + token.length,
            input.indexOf(keyword) + keyword.length,
        )
    }

    @Test
    fun `metadata and toString do not expose raw sensitive values`() {
        val keywordRule = SensitiveRedactionRule.keyword("keyword.safe", "keyword", keyword)
        val keywordPolicy = SensitiveRedactionPolicy.of(rules = listOf(keywordRule))
        val result = pipeline.redact("Support note $email $token")
        val rendered = result.toString() +
            result.spans.joinToString() +
            keywordRule.toString() +
            keywordPolicy.toString()

        rendered shouldNotContain email
        rendered shouldNotContain token
        rendered shouldNotContain keyword
        result.spans.forEach { span ->
            span.matchedLength shouldBeEqualTo (span.range.endExclusive - span.range.startInclusive)
            span.ruleIds.any { it.contains("@") }.shouldBeFalse()
        }
    }

    @Test
    fun `overlapping spans merge and adjacent spans stay separate`() {
        val policy = SensitiveRedactionPolicy.of(
            rules = listOf(
                SensitiveRedactionRule.keyword("keyword.low", "keyword", "account", priority = 30),
                SensitiveRedactionRule.keyword("keyword.high", "keyword", "account number", priority = 10),
                SensitiveRedactionRule.keyword("keyword.next", "keyword", "review", priority = 30),
            )
        )
        val localPipeline = SensitiveTextRedactionPipeline.of(policy)

        val result = localPipeline.redact("account number review")

        result.spans shouldHaveSize 2
        result.spans.first().range.startInclusive shouldBeEqualTo 0
        result.spans.first().range.endExclusive shouldBeEqualTo "account number".length
        result.spans.first().ruleIds shouldBeEqualTo listOf("keyword.high", "keyword.low")
    }

    @Test
    fun `adjacent keyword spans do not merge`() {
        val policy = SensitiveRedactionPolicy.of(
            rules = listOf(
                SensitiveRedactionRule.keyword("keyword.abc", "keyword", "abc"),
                SensitiveRedactionRule.keyword("keyword.def", "keyword", "def"),
            )
        )

        val result = SensitiveTextRedactionPipeline.of(policy).redact("abcdef")

        result.spans shouldHaveSize 2
        result.spans.map { it.range } shouldBeEqualTo listOf(
            SensitiveTextRange.of(0, 3),
            SensitiveTextRange.of(3, 6),
        )
    }

    @Test
    fun `equal priority overlaps choose category by rule id then category`() {
        val policy = SensitiveRedactionPolicy.of(
            rules = listOf(
                SensitiveRedactionRule.keyword("keyword.beta", "beta", "account", priority = 20),
                SensitiveRedactionRule.keyword("keyword.alpha", "alpha", "account number", priority = 20),
            )
        )

        val result = SensitiveTextRedactionPipeline.of(policy).redact("account number")

        result.spans shouldHaveSize 1
        result.spans.single().category shouldBeEqualTo "alpha"
        result.spans.single().ruleIds shouldBeEqualTo listOf("keyword.alpha", "keyword.beta")
    }

    @Test
    fun `keyword detector inclusive end converts to half open range`() {
        val result = SensitiveTextRedactionPipeline.of(
            SensitiveRedactionPolicy.of(
                rules = listOf(SensitiveRedactionRule.keyword("keyword.invoice", "keyword", "invoice"))
            )
        ).redact("open invoice")

        result.spans.single().range shouldBeEqualTo SensitiveTextRange.of(5, 12)
        result.spans.single().matchedLength shouldBeEqualTo "invoice".length
    }

    @Test
    fun `preserves original offsets when source contains decomposed Unicode`() {
        val composed = "cafe\u00e9"
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        val input = "mark $decomposed done"
        val result = SensitiveTextRedactionPipeline.of(
            SensitiveRedactionPolicy.of(
                rules = listOf(SensitiveRedactionRule.keyword("keyword.cafe", "keyword", composed))
            )
        ).redact(input)

        result.redactedText.length shouldBeEqualTo input.length
        result.spans.single().range.startInclusive shouldBeEqualTo input.indexOf(decomposed)
        result.spans.single().range.endExclusive shouldBeEqualTo input.indexOf(decomposed) + decomposed.length
        result.spans.single().matchedLength shouldBeEqualTo decomposed.length
        result.redactedText.substring(result.spans.single().range.startInclusive, result.spans.single().range.endExclusive)
            .all { it == '*' }
            .shouldBeTrue()
    }

    @Test
    fun `NFKC redacts compatibility expansion with the original source span`() {
        val input = "계약 대상은 ㈜블루테이프입니다"
        val result = SensitiveTextRedactionPipeline.of(
            SensitiveRedactionPolicy.of(
                rules = listOf(SensitiveRedactionRule.keyword("keyword.corp", "keyword", "(주)")),
                keywordNormalization = NormalizationForm.NFKC,
            )
        ).redact(input)

        val originalIndex = input.indexOf('㈜')
        result.spans.single().range shouldBeEqualTo SensitiveTextRange.of(originalIndex, originalIndex + 1)
        result.spans.single().matchedLength shouldBeEqualTo 1
        result.redactedText shouldBeEqualTo input.replace('㈜', '*')
        result.redactedText.length shouldBeEqualTo input.length
    }

    @Test
    fun `NFKC keeps adjacent compatibility matches separate`() {
        val result = SensitiveTextRedactionPipeline.of(
            SensitiveRedactionPolicy.of(
                rules = listOf(SensitiveRedactionRule.keyword("keyword.corp", "keyword", "(주)")),
                keywordNormalization = NormalizationForm.NFKC,
            )
        ).redact("㈜㈜")

        result.spans.map { it.range } shouldBeEqualTo listOf(
            SensitiveTextRange.of(0, 1),
            SensitiveTextRange.of(1, 2),
        )
        result.redactedText shouldBeEqualTo "**"
    }

    @Test
    fun `NFKC rejects an oversized normalization segment without exposing input`() {
        val pipeline = SensitiveTextRedactionPipeline.of(
            SensitiveRedactionPolicy.of(
                rules = listOf(SensitiveRedactionRule.keyword("keyword.safe", "keyword", "safe")),
                keywordNormalization = NormalizationForm.NFKC,
            )
        )
        val raw = "a" + "\u0301".repeat(1_025)

        val failure = assertFailsWith<IllegalArgumentException> {
            pipeline.redact(raw)
        }

        failure.message.orEmpty() shouldContain "normalization segment too long"
        failure.message.orEmpty() shouldContain "max 1024"
        failure.message.orEmpty() shouldNotContain raw.take(80)
    }

    @Test
    fun `returns language metadata for multilingual Korean and English input`() {
        val input = "Support note: 서울 카페 예약 담당자는 $email 로 연락했습니다."

        val result = pipeline.redact(input)

        result.detectedLanguage.shouldNotBeNull()
        (result.bestConfidence != null).shouldBeTrue()
        result.spans.single().range shouldBeEqualTo SensitiveTextRange.of(input.indexOf(email), input.indexOf(email) + email.length)
    }

    @Test
    fun `rejects blank text without echoing caller input`() {
        val raw = "   "

        val failure = assertFailsWith<IllegalArgumentException> {
            pipeline.redact(raw)
        }

        (failure.message ?: "") shouldNotContain raw
    }

    @Test
    fun `rejects over limit text without echoing caller input`() {
        val raw = "a".repeat(SensitiveRedactionPolicy.DEFAULT_MAX_TEXT_LENGTH + 1)

        val failure = assertFailsWith<IllegalArgumentException> {
            pipeline.redact(raw)
        }

        (failure.message ?: "") shouldNotContain raw.take(80)
    }

    @Test
    fun `rejects unsafe rule ids and categories`() {
        val unsafeValues = listOf(
            "Keyword",
            "has space",
            "slash/value",
            email,
            phone,
            token,
            "customer-123456",
            "ticket-123456",
            keyword,
        )

        unsafeValues.forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                SensitiveRedactionRule.keyword(value, "keyword", keyword)
            }
            assertFailsWith<IllegalArgumentException> {
                SensitiveRedactionRule.keyword("keyword.safe", value, keyword)
            }
        }
    }

    @Test
    fun `rejects invalid ranges masks empty rules and unsafe regex sources`() {
        assertFailsWith<IllegalArgumentException> { SensitiveTextRange.of(-1, 2) }
        assertFailsWith<IllegalArgumentException> { SensitiveTextRange.of(2, 2) }
        assertFailsWith<IllegalArgumentException> { SensitiveTextRange.of(3, 2) }
        assertFailsWith<IllegalArgumentException> { SensitiveRedactionPolicy.of(rules = emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            SensitiveRedactionPolicy.of(
                rules = listOf(SensitiveRedactionRule.keyword("keyword.safe", "keyword", keyword)),
                maskChar = ' ',
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SensitiveRedactionRule.regex("regex.backref", "secret", """(a)\1""")
        }
        assertFailsWith<IllegalArgumentException> {
            SensitiveRedactionRule.regex("regex.nested", "secret", """(a+)+""")
        }
        assertFailsWith<IllegalArgumentException> {
            SensitiveRedactionRule.regex("regex.dotstar", "secret", """.*secret.*""")
        }
    }

    @Test
    fun `handles long non matching token candidates without catastrophic regex behavior`() {
        val longInput = "a".repeat(SensitiveRedactionPolicy.DEFAULT_MAX_TEXT_LENGTH - 32) + " token="

        val elapsed = measureTime {
            pipeline.redact(longInput)
        }

        elapsed.inWholeMilliseconds shouldBeLessThan 300L
    }

    @Test
    fun `debug logs include safe metadata and exclude synthetic sensitive values`() {
        val logs = captureWorkshopLogs {
            pipeline.redact("Contact $email at $phone with $token for $keyword")
            LanguageDetectionService().detectLanguage("Contact $email")
            TextNormalizer.normalize("Contact $email")
            TextNormalizer.extractKeywords("Contact $keyword")
            AbuseWordFilter(listOf(keyword)).findMatches("Contact $keyword")
            MultilingualSearchIndex.indexOf(
                listOf(SearchDocument.of("doc-1", "support", "Contact $email"))
            ).search("Contact $email")
            runSuspendDefault {
                CoroutineMultilingualSearchIndex.indexOf(
                    listOf(SearchDocument.of("doc-2", "support", "Contact $email"))
                ).search("Contact $email")
            }
        }

        logs.shouldNotBeEmpty()
        logs.any { it.contains("length=") || it.contains("hits=") || it.contains("matches=") }.shouldBeTrue()
        val rendered = logs.joinToString("\n")
        rendered shouldNotContain email
        rendered shouldNotContain phone
        rendered shouldNotContain token
        rendered shouldNotContain keyword
        rendered shouldNotContain "contact $keyword"
    }

    @Test
    fun `policy snapshots do not change when caller mutates original rule list`() {
        val rules = mutableListOf(SensitiveRedactionRule.keyword("keyword.account", "keyword", keyword))
        val pipeline = SensitiveTextRedactionPipeline.of(SensitiveRedactionPolicy.of(rules))
        val before = pipeline.redact(keyword)

        rules += SensitiveRedactionRule.keyword("keyword.extra", "keyword", "review")
        val after = pipeline.redact(keyword)

        after shouldBeEqualTo before
    }

    @Test
    fun `shared pipeline is stable under MultithreadingTester`() {
        val baseline = pipeline.redact("Contact $email with $token")
        val outputs = ConcurrentLinkedQueue<SensitiveRedactionResult>()

        MultithreadingTester()
            .workers(8)
            .rounds(16)
            .add {
                outputs += pipeline.redact("Contact $email with $token")
            }
            .run()

        outputs shouldHaveSize 128
        outputs.forEach { result ->
            result shouldBeEqualTo baseline
            result.redactedText shouldNotContain email
            result.redactedText shouldNotContain token
        }
    }

    private fun captureWorkshopLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("io.bluetape4k.workshop.text") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val previousLevel = logger.level
        val previousAdditive = logger.isAdditive
        logger.level = Level.DEBUG
        logger.isAdditive = true
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            logger.isAdditive = previousAdditive
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }
}
