package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.support.requireNotNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DeterministicScheduleTest {

    @Test
    fun `every versioned golden vector produces the exact canonical tokens`() {
        val vectors = HighContentionContractLoader().load(
            contractRoot = contractRoot(),
            mode = HighContentionMode.CI_CORRECTNESS,
        ).scheduleVectors

        vectors.vectors.forEach { vector ->
            DeterministicSchedule.generate(vector) shouldBeEqualTo vector.expectedTokens
        }
    }

    @Test
    fun `the same vector always produces the same canonical digest`() {
        val vector = HighContentionContractLoader().load(
            contractRoot = contractRoot(),
            mode = HighContentionMode.CI_CORRECTNESS,
        ).scheduleVectors.vectors.last()

        val first = DeterministicSchedule.generate(vector)
        val second = DeterministicSchedule.generate(vector)

        second shouldBeEqualTo first
        DeterministicSchedule.digest(second) shouldBeEqualTo DeterministicSchedule.digest(first)
    }

    @Test
    fun `offset arithmetic remains bounded at Long maximum duration`() {
        val vector = ScheduleVector(
            name = "long-boundary",
            profileSchemaVersion = 1,
            seed = "boundary",
            curve = ArrivalCurve.STEP,
            operationCount = 3,
            durationNanos = Long.MAX_VALUE,
            authorityWeights = listOf(1),
            epochs = listOf(ScheduleEpoch(Long.MAX_VALUE, 3)),
            retryShape = null,
            expectedTokens = emptyList(),
        )

        val schedule = DeterministicSchedule.generate(vector)

        schedule.map(ScheduleToken::offsetNanos) shouldBeEqualTo listOf(
            0L,
            Long.MAX_VALUE / 3,
            (Long.MAX_VALUE.toBigInteger() * 2.toBigInteger() / 3.toBigInteger()).toLong(),
        )
    }

    @Test
    fun `invalid retry totals are rejected before schedule generation`() {
        val vector = ScheduleVector(
            name = "invalid-retry",
            profileSchemaVersion = 1,
            seed = "invalid",
            curve = ArrivalCurve.RETRY_STORM,
            operationCount = 3,
            durationNanos = 100,
            authorityWeights = listOf(1),
            epochs = emptyList(),
            retryShape = ScheduleRetryShape(identityCount = 2, attemptsPerIdentity = 2),
            expectedTokens = emptyList(),
        )

        assertFailsWith<IllegalArgumentException> {
            DeterministicSchedule.generate(vector)
        }
    }

    private fun contractRoot(): Path =
        Path.of(System.getProperty("highContentionContractRoot").requireNotNull("highContentionContractRoot"))
}
