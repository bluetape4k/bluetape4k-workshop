package io.bluetape4k.workshop.flow.metrics.sampling

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant
import kotlin.math.abs

/**
 * 하나의 high-frequency metric 또는 sensor value 입니다.
 *
 * ## Contract
 * - `name` 과 `unit` 은 trim 되고 길이가 제한되며 control character 를 포함하지 않아 workshop log 에 안전하게 표시할 수 있습니다.
 * - `value` 는 finite 값이어야 합니다.
 * - private constructor 로 검증된 field 를 public `copy` 가 우회하지 못하게 합니다.
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
            value.requireFiniteNumber("value")
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
 * 같은 metric stream 에서 인접한 두 sample 사이의 delta 입니다.
 *
 * ## Contract
 * - sample 들은 하나의 metric name 과 unit 을 공유해야 합니다.
 * - `delta` 는 `current.value - previous.value` 입니다.
 * - previous value 가 0이면 `percentChange` 는 `null` 입니다.
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
 * metric transition 의 방향입니다.
 */
enum class MetricDirection {
    UP,
    DOWN,
    UNCHANGED
}

/**
 * adjacent delta 에서 파생한 significant 또는 non-significant trend 입니다.
 */
data class MetricTrend(
    val delta: MetricDelta,
    val direction: MetricDirection,
    val significant: Boolean,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -1095298694973748477L

        fun from(delta: MetricDelta, absoluteThreshold: Double): MetricTrend {
            absoluteThreshold.requirePositiveFiniteNumber("absoluteThreshold")

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

internal fun Double.requireFiniteNumber(parameterName: String): Double {
    require(isFinite()) { "$parameterName must be finite" }
    return this
}

internal fun Double.requirePositiveFiniteNumber(parameterName: String): Double =
    requireFiniteNumber(parameterName).requirePositiveNumber(parameterName)
