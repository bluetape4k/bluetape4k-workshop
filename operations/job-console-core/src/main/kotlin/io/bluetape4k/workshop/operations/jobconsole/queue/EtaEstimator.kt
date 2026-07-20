package io.bluetape4k.workshop.operations.jobconsole.queue

import io.bluetape4k.workshop.operations.jobconsole.api.EtaConfidence
import io.bluetape4k.workshop.operations.jobconsole.api.TimeRange
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

data class EtaEstimate(
    val startRange: TimeRange?,
    val completionRange: TimeRange?,
    val confidence: EtaConfidence,
    val sampleSize: Int,
)

class EtaEstimator(
    private val minimumSampleSize: Int = 3,
) {
    fun estimate(samples: List<Duration>, jobsAhead: Int, now: Instant): EtaEstimate {
        require(jobsAhead >= 0) { "jobsAhead must not be negative" }
        val valid = samples.filter { !it.isNegative && !it.isZero }.sorted()
        if (valid.size < minimumSampleSize) {
            return EtaEstimate(null, null, EtaConfidence.INSUFFICIENT_DATA, valid.size)
        }
        val p50 = percentile(valid, 0.50)
        val p90 = percentile(valid, 0.90)
        val earliestStart = now.plus(p50.multipliedBy(jobsAhead.toLong()))
        val latestStart = now.plus(p90.multipliedBy(jobsAhead.toLong()))
        return EtaEstimate(
            startRange = TimeRange(earliestStart, latestStart),
            completionRange = TimeRange(earliestStart.plus(p50), latestStart.plus(p90)),
            confidence = confidence(valid.size),
            sampleSize = valid.size,
        )
    }

    private fun percentile(sorted: List<Duration>, percentile: Double): Duration =
        sorted[(ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)]

    private fun confidence(sampleSize: Int): EtaConfidence =
        when {
            sampleSize >= 20 -> EtaConfidence.HIGH
            sampleSize >= 10 -> EtaConfidence.MEDIUM
            else -> EtaConfidence.LOW
        }
}
