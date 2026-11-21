package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import kotlin.math.ceil

/**
 * `HighContentionPercentileStatus`은 상태 코드입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
enum class HighContentionPercentileStatus {
    /**
     * `HighContentionPercentileStatus.MEASURED`은 `measured` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와
     * terminal 여부를 확인합니다.
     */
    MEASURED,
    /**
     * `HighContentionPercentileStatus.INSUFFICIENT_SAMPLES`은 `insufficient samples` 업무 상황을 나타냅니다.
     * 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    INSUFFICIENT_SAMPLES,
    /**
     * `HighContentionPercentileStatus.NOT_APPLICABLE`은 `not applicable` 업무 상황을 나타냅니다. 현재 상태에서 허용된
     * 다음 전이와 terminal 여부를 확인합니다.
     */
    NOT_APPLICABLE,
}

/**
 * HighContentionPercentile 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionPercentile(
/**
 * status은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val status: HighContentionPercentileStatus,
/**
 * valueNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val valueNanos: Long? = null,
) {
    init {
        if ((status == HighContentionPercentileStatus.MEASURED) != (valueNanos != null)) {
            throw IllegalArgumentException("only MEASURED percentiles carry a value")
        }
        valueNanos?.requireZeroOrPositiveNumber("valueNanos")
    }
}

/**
 * HighContentionPercentiles 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionPercentiles(
/**
 * p50은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val p50: HighContentionPercentile,
/**
 * p95은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val p95: HighContentionPercentile,
/**
 * p99은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val p99: HighContentionPercentile,
)

/**
 * HighContentionSaturationSample 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionSaturationSample(
/**
 * atNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val atNanos: Long,
/**
 * used은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val used: Int,
/**
 * capacity은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val capacity: Int,
)

/**
 * HighContentionSaturationSummary 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionSaturationSummary(
/**
 * sampleCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sampleCount: Int,
/**
 * maxUsed은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val maxUsed: Int,
/**
 * timeAtCapacityNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val timeAtCapacityNanos: Long,
)

/**
 * HighContentionMeasurements 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
object HighContentionMeasurements {

/**
 * percentiles은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun percentiles(samples: List<Long>): HighContentionPercentiles {
        samples.forEach { it.requireZeroOrPositiveNumber("latency sample") }
        val sorted = samples.sorted()
        return HighContentionPercentiles(
            p50 = percentile(sorted, 0.50, 2),
            p95 = percentile(sorted, 0.95, 20),
            p99 = percentile(sorted, 0.99, 100),
        )
    }

/**
 * notApplicablePercentiles은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun notApplicablePercentiles(): HighContentionPercentiles =
        HighContentionPercentile(HighContentionPercentileStatus.NOT_APPLICABLE)
            .let { HighContentionPercentiles(it, it, it) }

/**
 * saturation은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun saturation(
        samples: List<HighContentionSaturationSample>,
        lastTerminalNanos: Long,
    ): HighContentionSaturationSummary {
        samples.requireNotEmpty("saturation samples")
/**
 * validSamples은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * timeAtCapacityNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var timeAtCapacityNanos = 0L
        validSamples.forEachIndexed { index, sample ->
/**
 * intervalEnd은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * percentile은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun percentile(
        sortedSamples: List<Long>,
        percentile: Double,
        minimumSampleCount: Int,
    ): HighContentionPercentile {
        if (sortedSamples.size < minimumSampleCount) {
            return HighContentionPercentile(HighContentionPercentileStatus.INSUFFICIENT_SAMPLES)
        }
/**
 * index은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val index = ceil(percentile * sortedSamples.size).toInt() - 1
        return HighContentionPercentile(
            status = HighContentionPercentileStatus.MEASURED,
            valueNanos = sortedSamples[index],
        )
    }
}
