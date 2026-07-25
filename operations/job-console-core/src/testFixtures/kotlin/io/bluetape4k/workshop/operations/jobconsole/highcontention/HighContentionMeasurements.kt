package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import kotlin.math.ceil

enum class HighContentionPercentileStatus {
    MEASURED,
    INSUFFICIENT_SAMPLES,
    NOT_APPLICABLE,
}

data class HighContentionPercentile(
    val status: HighContentionPercentileStatus,
    val valueNanos: Long? = null,
) {
    init {
        if ((status == HighContentionPercentileStatus.MEASURED) != (valueNanos != null)) {
            throw IllegalArgumentException("only MEASURED percentiles carry a value")
        }
        valueNanos?.requireZeroOrPositiveNumber("valueNanos")
    }
}

data class HighContentionPercentiles(
    val p50: HighContentionPercentile,
    val p95: HighContentionPercentile,
    val p99: HighContentionPercentile,
)

data class HighContentionSaturationSample(
    val atNanos: Long,
    val used: Int,
    val capacity: Int,
)

data class HighContentionSaturationSummary(
    val sampleCount: Int,
    val maxUsed: Int,
    val timeAtCapacityNanos: Long,
)

object HighContentionMeasurements {

    fun percentiles(samples: List<Long>): HighContentionPercentiles {
        samples.forEach { it.requireZeroOrPositiveNumber("latency sample") }
        val sorted = samples.sorted()
        return HighContentionPercentiles(
            p50 = percentile(sorted, 0.50, 2),
            p95 = percentile(sorted, 0.95, 20),
            p99 = percentile(sorted, 0.99, 100),
        )
    }

    fun notApplicablePercentiles(): HighContentionPercentiles =
        HighContentionPercentile(HighContentionPercentileStatus.NOT_APPLICABLE)
            .let { HighContentionPercentiles(it, it, it) }

    fun saturation(
        samples: List<HighContentionSaturationSample>,
        lastTerminalNanos: Long,
    ): HighContentionSaturationSummary {
        samples.requireNotEmpty("saturation samples")
        val validSamples = samples
        validSamples.forEach { sample ->
            sample.atNanos.requireZeroOrPositiveNumber("sample.atNanos")
            sample.used.requireZeroOrPositiveNumber("sample.used")
            sample.capacity.requirePositiveNumber("sample.capacity")
            sample.used.requireLe(sample.capacity, "sample.used")
        }
        if (validSamples.zipWithNext().any { (left, right) -> left.atNanos >= right.atNanos }) {
            throw IllegalArgumentException("saturation samples must be strictly ordered")
        }
        lastTerminalNanos.requireGe(validSamples.first().atNanos, "lastTerminalNanos")
        validSamples.last().atNanos.requireLe(lastTerminalNanos, "final sample atNanos")

        var timeAtCapacityNanos = 0L
        validSamples.forEachIndexed { index, sample ->
            val intervalEnd = minOf(
                validSamples.getOrNull(index + 1)?.atNanos ?: lastTerminalNanos,
                lastTerminalNanos,
            )
            if (sample.atNanos < lastTerminalNanos && sample.used == sample.capacity) {
                timeAtCapacityNanos = Math.addExact(
                    timeAtCapacityNanos,
                    intervalEnd - sample.atNanos,
                )
            }
        }
        return HighContentionSaturationSummary(
            sampleCount = validSamples.size,
            maxUsed = validSamples.maxOf(HighContentionSaturationSample::used),
            timeAtCapacityNanos = timeAtCapacityNanos,
        )
    }

    private fun percentile(
        sortedSamples: List<Long>,
        percentile: Double,
        minimumSampleCount: Int,
    ): HighContentionPercentile {
        if (sortedSamples.size < minimumSampleCount) {
            return HighContentionPercentile(HighContentionPercentileStatus.INSUFFICIENT_SAMPLES)
        }
        val index = ceil(percentile * sortedSamples.size).toInt() - 1
        return HighContentionPercentile(
            status = HighContentionPercentileStatus.MEASURED,
            valueNanos = sortedSamples[index],
        )
    }
}
