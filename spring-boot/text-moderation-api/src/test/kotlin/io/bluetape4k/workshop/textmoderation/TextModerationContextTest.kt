package io.bluetape4k.workshop.textmoderation

import com.github.pemistahl.lingua.api.LanguageDetector
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.workshop.textmoderation.service.VersionedModerationDictionary
import io.bluetape4k.workshop.textmoderation.service.TextModerationService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.runner.ApplicationContextRunner

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextModerationContextTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TextModerationApplication::class.java)

    @Test
    fun `context reuses one detector and one automaton bean`() {
        contextRunner.run { context ->
            val detector1 = context.getBean(LanguageDetector::class.java)
            val detector2 = context.getBean(LanguageDetector::class.java)
            val automaton1 = context.getBean(AhoCorasickAutomaton::class.java)
            val automaton2 = context.getBean(AhoCorasickAutomaton::class.java)
            val dictionary1 = context.getBean(VersionedModerationDictionary::class.java)
            val dictionary2 = context.getBean(VersionedModerationDictionary::class.java)

            context.getBean(TextModerationApplication::class.java).shouldNotBeNull()
            detector1 shouldBeSameInstanceAs detector2
            automaton1 shouldBeSameInstanceAs automaton2
            dictionary1 shouldBeSameInstanceAs dictionary2
        }
    }

    @Test
    fun `NFKC property detects compatibility input and preserves source length`() {
        contextRunner
            .withPropertyValues(
                "workshop.text-moderation.normalization=NFKC",
                "workshop.text-moderation.blockwords[0]=(주)",
            )
            .run { context ->
                val response = context.getBean(TextModerationService::class.java).analyze("회사명: ㈜블루테이프")

                response.matchedTerms shouldBeEqualTo listOf("(주)")
                response.maskedText shouldBeEqualTo "회사명: *블루테이프"
            }
    }
}
