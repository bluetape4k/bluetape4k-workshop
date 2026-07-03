package io.bluetape4k.workshop.flow.metrics.sampling

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant
import kotlin.math.abs

/**
 * One high-frequency metric or sensor value.
 *
 * ## Contract
 * - `name` and `unit` are trimmed, bounded, and control-character free so
 *   sample values are safe to show in workshop logs.
 * - `value` must be finite.
 * - A private constructor avoids public `copy` bypasses for validated fields.
 *
 * ```kotlin
 * val sample = MetricSample.of("cpu.usage", 71.5, Instant.now(), "percent")
 * ```
 */
class MetricSample private constructor(
    val name: String,
    val value: Double,
    val timestamp: Instant,
    val unit: String,
): Serializable {

    override fun toString(): String =
        "MetricSample(name=$name, value=$value, unit=$unit, timestamp=$timestamp)"

    companion object {
        private const val serialVersionUID: Long = 5931197209440246714L

        fun of(
            name: String,
            value: Double,
            timestamp: Instant,
            unit: String = "value",
        ): MetricSample {
            require(value.isFinite()) { "value must be finite" }
            return MetricSample(
                name = normalizeToken(name, "name", maxLength = 80),
                value = value,
                timestamp = timestamp,
                unit = normalizeToken(unit, "unit", maxLength = 32),
            )
        }
    }
}

/**
 * Adjacent delta between two samples from the same metric stream.
 *
 * ## Contract
 * - The samples must share one metric name and unit.
 * - `delta` is `current.value - previous.value`.
 * - `percentChange` is `null` when the previous value is zero.
 */
data class MetricDelta(
    val name: String,
    val unit: String,
    val previous: MetricSample,
    val current: MetricSample,
    val delta: Double,
    val percentChange: Double?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 6189980319671351175L

        fun from(previous: MetricSample, current: MetricSample): MetricDelta {
            require(previous.name == current.name) { "metric sample names must match" }
            require(previous.unit == current.unit) { "metric sample units must match" }

            val delta = current.value - previous.value
            val percentChange = if (previous.value == 0.0) {
                null
            } else {
                delta / abs(previous.value) * 100.0
            }

            return MetricDelta(
                name = current.name,
                unit = current.unit,
                previous = previous,
                current = current,
                delta = delta,
                percentChange = percentChange,
            )
        }
    }
}

/**
 * Direction of a metric transition.
 */
enum class MetricDirection {
    UP,
    DOWN,
    UNCHANGED
}

/**
 * Significant or non-significant trend derived from an adjacent delta.
 */
data class MetricTrend(
    val delta: MetricDelta,
    val direction: MetricDirection,
    val significant: Boolean,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -1095298694973748477L

        fun from(delta: MetricDelta, absoluteThreshold: Double): MetricTrend {
            require(absoluteThreshold.isFinite() && absoluteThreshold > 0.0) {
                "absoluteThreshold must be positive and finite"
            }

            return MetricTrend(
                delta = delta,
                direction = when {
                    delta.delta > 0.0 -> MetricDirection.UP
                    delta.delta < 0.0 -> MetricDirection.DOWN
                    else              -> MetricDirection.UNCHANGED
                },
                significant = abs(delta.delta) >= absoluteThreshold,
            )
        }
    }
}

private fun normalizeToken(value: String, label: String, maxLength: Int): String {
    val normalized = value.trim()
    normalized.requireNotBlank(label)
    normalized.length.requireInRange(1, maxLength, "$label.length")
    require(normalized.none(Char::isISOControl)) { "$label must not contain control characters" }
    return normalized
}
