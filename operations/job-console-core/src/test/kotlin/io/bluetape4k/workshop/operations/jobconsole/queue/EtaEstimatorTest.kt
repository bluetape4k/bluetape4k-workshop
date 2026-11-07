package io.bluetape4k.workshop.operations.jobconsole.queue

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.api.EtaConfidence
import org.junit.jupiter.api.Test
import java.time.Instant

class EtaEstimatorTest {

    companion object: KLogging() {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }

    private val estimator = EtaEstimator(minimumSampleSize = 3)

    @Test
    fun `insufficient samples never fabricate a precise ETA`() {
        val estimate = estimator.estimate(emptyList(), jobsAhead = 3, now = NOW)

        estimate.confidence shouldBeEqualTo EtaConfidence.INSUFFICIENT_DATA
        estimate.startRange.shouldBeNull()
        estimate.completionRange.shouldBeNull()
    }

    @Test
    fun `p50 and p90 produce an honest bounded range`() {
        val samples = listOf(10, 20, 30, 40, 50).map { it.seconds() }

        val estimate = estimator.estimate(samples, jobsAhead = 2, now = NOW)

        estimate.startRange?.earliest shouldBeEqualTo NOW + 60.seconds()
        estimate.startRange?.latest shouldBeEqualTo NOW + 100.seconds()
        estimate.completionRange?.earliest shouldBeEqualTo NOW + 90.seconds()
        estimate.completionRange?.latest shouldBeEqualTo NOW + 150.seconds()
        estimate.sampleSize shouldBeEqualTo 5
    }
}
