package io.bluetape4k.workshop.text.filter

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.text.search.NormalizationForm
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.text.Normalizer
import kotlin.coroutines.cancellation.CancellationException

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
    fun `findMatchesAsFlow preserves synchronous match order and overlap`() = runSuspendIO {
        val overlapFilter = AbuseWordFilter(listOf("he", "she", "hers"))
        val input = "ushers"

        val synchronous = overlapFilter.findMatches(input)
        val flow = overlapFilter.findMatchesAsFlow(input)
        val firstCollection = flow.toList()
        val secondCollection = flow.toList()

        firstCollection shouldBeEqualTo synchronous
        secondCollection shouldBeEqualTo synchronous
        firstCollection.map { it.value } shouldBeEqualTo listOf("he", "she", "hers")
    }

    @Test
    fun `findMatchesAsFlow returns empty for empty dictionary and clean text`() = runSuspendIO {
        AbuseWordFilter(emptyList()).findMatchesAsFlow("spam").toList() shouldHaveSize 0
        filter.findMatchesAsFlow("everything is fine here").toList() shouldHaveSize 0
    }

    @Test
    fun `findMatchesAsFlow take one cancels upstream after the first emitted match`() = runSuspendIO {
        var emitted = 0
        var completionCause: Throwable? = null
        val input = List(1_024) { "spam" }.joinToString(" ")
        val matches = filter.findMatchesAsFlow(input)
            .onCompletion { completionCause = it }
            .onEach { emitted++ }
            .take(1)
            .toList()

        matches shouldHaveSize 1
        matches.single().value shouldBeEqualTo "spam"
        emitted shouldBeEqualTo 1
        (completionCause is CancellationException).shouldBeTrue()
    }

    @Test
    fun `findMatchesAsFlow preserves NFC and NFKC source offsets`() = runSuspendIO {
        val decomposed = Normalizer.normalize("café", Normalizer.Form.NFD)
        val nfcInput = "menu: $decomposed"
        val nfcMatch = AbuseWordFilter(listOf("café"))
            .findMatchesAsFlow(nfcInput)
            .toList()
            .single()
        val nfkcInput = "회사명: ㈜블루테이프"
        val nfkcMatch = AbuseWordFilter(listOf("(주)"), NormalizationForm.NFKC)
            .findMatchesAsFlow(nfkcInput)
            .toList()
            .single()

        nfcMatch.start shouldBeEqualTo nfcInput.indexOf('c')
        nfcMatch.end shouldBeEqualTo nfcInput.lastIndex
        nfkcMatch.start shouldBeEqualTo nfkcInput.indexOf('㈜')
        nfkcMatch.end shouldBeEqualTo nfkcInput.indexOf('㈜')
    }

    @Test
    fun `filter with empty word list never matches`() {
        val emptyFilter = AbuseWordFilter(emptyList())
        emptyFilter.containsAbuse("any text including badword").shouldBeFalse()
        emptyFilter.findMatches("spam") shouldHaveSize 0
    }

    @Test
    fun `preserves the JVM one argument constructor`() {
        val constructor = AbuseWordFilter::class.java.getConstructor(Collection::class.java)

        constructor.newInstance(listOf("spam")).containsAbuse("spam").shouldBeTrue()
    }

    @Test
    fun `NFKC maps compatibility expansion to the original source range`() {
        val nfkcFilter = AbuseWordFilter(listOf("(주)"), NormalizationForm.NFKC)
        val input = "회사명: ㈜블루테이프"

        AbuseWordFilter(listOf("(주)")).containsAbuse(input).shouldBeFalse()
        val match = nfkcFilter.findMatches(input).single()

        match.start shouldBeEqualTo input.indexOf('㈜')
        match.end shouldBeEqualTo input.indexOf('㈜')
        nfkcFilter.filterText(input) shouldBeEqualTo "회사명: *블루테이프"
    }

    @Test
    fun `NFKC rejects an oversized normalization segment without exposing input`() {
        val nfkcFilter = AbuseWordFilter(listOf("safe"), NormalizationForm.NFKC)
        val raw = "a" + "\u0301".repeat(1_025)

        val failure = assertFailsWith<IllegalArgumentException> {
            nfkcFilter.findMatches(raw)
        }

        failure.message.orEmpty() shouldContain "normalization segment too long"
        failure.message.orEmpty() shouldContain "max 1024"
        failure.message.orEmpty() shouldNotContain raw.take(80)
    }
}
