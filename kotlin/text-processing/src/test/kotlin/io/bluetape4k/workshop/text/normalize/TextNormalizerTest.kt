package io.bluetape4k.workshop.text.normalize

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextNormalizerTest {

    companion object : KLogging()

    @Test
    fun `normalize lower-cases the input`() {
        TextNormalizer.normalize("Hello WORLD") shouldBeEqualTo "hello world"
    }

    @Test
    fun `normalize collapses multiple spaces to single space`() {
        TextNormalizer.normalize("too   many    spaces") shouldBeEqualTo "too many spaces"
    }

    @Test
    fun `normalize trims leading and trailing whitespace`() {
        TextNormalizer.normalize("  padded  ") shouldBeEqualTo "padded"
    }

    @Test
    fun `normalize returns empty string for blank input`() {
        TextNormalizer.normalize("") shouldBeEqualTo ""
        TextNormalizer.normalize("   ") shouldBeEqualTo ""
    }

    @Test
    fun `normalize handles mixed whitespace characters`() {
        TextNormalizer.normalize("tab\there\nnewline") shouldBeEqualTo "tab here newline"
    }

    @Test
    fun `extractKeywords returns tokens meeting minimum length`() {
        val keywords = TextNormalizer.extractKeywords("the quick brown fox")
        keywords shouldContain "quick"
        keywords shouldContain "brown"
        keywords shouldContain "the"
    }

    @Test
    fun `extractKeywords filters tokens shorter than minKeywordLength`() {
        val keywords = TextNormalizer.extractKeywords("a quick brown fox", minKeywordLength = 4)
        keywords shouldNotContain "a"
        keywords shouldContain "quick"
        keywords shouldContain "brown"
    }

    @Test
    fun `extractKeywords deduplicates repeated tokens`() {
        val keywords = TextNormalizer.extractKeywords("spam spam spam everyone")
        keywords shouldHaveSize 2
        keywords shouldContain "spam"
        keywords shouldContain "everyone"
    }

    @Test
    fun `extractKeywords returns empty list for blank input`() {
        TextNormalizer.extractKeywords("").shouldBeEmpty()
        TextNormalizer.extractKeywords("   ").shouldBeEmpty()
    }

    @Test
    fun `extractKeywords normalizes before tokenizing`() {
        val keywords = TextNormalizer.extractKeywords("  HELLO   WORLD  hello ")
        // "hello" and "world" after normalization; deduplication removes second "hello"
        keywords shouldHaveSize 2
        keywords shouldContain "hello"
        keywords shouldContain "world"
    }
}
