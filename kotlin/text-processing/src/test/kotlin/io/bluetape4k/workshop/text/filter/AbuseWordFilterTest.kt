package io.bluetape4k.workshop.text.filter

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbuseWordFilterTest {

    companion object : KLogging()

    private val filter = AbuseWordFilter(
        listOf("badword", "spam", "abuse", "hate")
    )

    @Test
    fun `containsAbuse returns true when text contains a registered abuse word`() {
        filter.containsAbuse("This message contains badword!").shouldBeTrue()
    }

    @Test
    fun `containsAbuse returns false when text has no abuse word`() {
        filter.containsAbuse("This is a perfectly clean message.").shouldBeFalse()
    }

    @Test
    fun `containsAbuse is case-insensitive`() {
        filter.containsAbuse("BADWORD in uppercase").shouldBeTrue()
        filter.containsAbuse("Spam mixed case").shouldBeTrue()
    }

    @Test
    fun `filterText replaces abuse words with asterisks`() {
        val result = filter.filterText("No spam allowed here")
        result shouldBeEqualTo "No **** allowed here"
    }

    @Test
    fun `filterText leaves clean text unchanged`() {
        val input = "This is a normal sentence."
        filter.filterText(input) shouldBeEqualTo input
    }

    @Test
    fun `filterText handles multiple abuse words in one sentence`() {
        val result = filter.filterText("badword and spam together")
        result shouldBeEqualTo "******* and **** together"
    }

    @Test
    fun `findMatches returns all matched positions`() {
        val matches = filter.findMatches("spam and abuse are badword")
        matches.shouldNotBeEmpty()
        matches shouldHaveSize 3
    }

    @Test
    fun `findMatches returns empty list for clean text`() {
        val matches = filter.findMatches("everything is fine here")
        matches shouldHaveSize 0
    }

    @Test
    fun `filter with empty word list never matches`() {
        val emptyFilter = AbuseWordFilter(emptyList())
        emptyFilter.containsAbuse("any text including badword").shouldBeFalse()
        emptyFilter.findMatches("spam") shouldHaveSize 0
    }
}
