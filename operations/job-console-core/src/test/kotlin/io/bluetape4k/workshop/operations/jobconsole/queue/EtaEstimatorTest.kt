package io.bluetape4k.workshop.operations.jobconsole.queue

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.EtaConfidence
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class EtaEstimatorTest {
    private val estimator = EtaEstimator(minimumSampleSize = 3)

    @Test
    fun `insufficient samples never fabricate a precise ETA`() {
        val estimate = estimator.estimate(emptyList(), jobsAhead = 3, now = NOW)

        estimate.confidence shouldBeEqualTo EtaConfidence.INSUFFICIENT_DATA
        estimate.startRange shouldBeEqualTo null
        estimate.completionRange shouldBeEqualTo null
    }

    @Test
    fun `p50 and p90 produce an honest bounded range`() {
        val samples = listOf(10, 20, 30, 40, 50).map { Duration.ofSeconds(it.toLong()) }

        val estimate = estimator.estimate(samples, jobsAhead = 2, now = NOW)

        estimate.startRange?.earliest shouldBeEqualTo NOW.plusSeconds(60)
        estimate.startRange?.latest shouldBeEqualTo NOW.plusSeconds(100)
        estimate.completionRange?.earliest shouldBeEqualTo NOW.plusSeconds(90)
        estimate.completionRange?.latest shouldBeEqualTo NOW.plusSeconds(150)
        estimate.sampleSize shouldBeEqualTo 5
    }

    companion object {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }
}
