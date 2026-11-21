package io.bluetape4k.workshop.operations.jobconsole.queue

import io.bluetape4k.workshop.operations.jobconsole.api.EtaConfidence
import io.bluetape4k.workshop.operations.jobconsole.api.TimeRange
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
/**
 * EtaEstimate은 운영 작업 콘솔에서 전달되는 불변 데이터 모델입니다.
 * @property startRange 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property completionRange 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property confidence 현재 운영 작업 콘솔 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property sampleSize 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 */


data class EtaEstimate(
    /**
     * `startRange`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val startRange: TimeRange?,
    /**
     * `completionRange`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val completionRange: TimeRange?,
    /**
     * `confidence`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val confidence: EtaConfidence,
    /**
     * `sampleSize`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val sampleSize: Int,
)
/**
 * EtaEstimator은 운영 작업 콘솔에서 사용하는 업무 객체입니다.
 * @property minimumSampleSize 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 */


class EtaEstimator(
    /**
     * `minimumSampleSize`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val minimumSampleSize: Int = 3,
) {
    /**
     * estimate은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param samples 업무 처리에 필요한 samples 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param jobsAhead 업무 처리에 필요한 jobsAhead 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
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

    /**
     * percentile은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param sorted 업무 처리에 필요한 sorted 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param percentile 업무 처리에 필요한 percentile 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun percentile(sorted: List<Duration>, percentile: Double): Duration =
        sorted[(ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)]

    /**
     * confidence은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param sampleSize 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    private fun confidence(sampleSize: Int): EtaConfidence =
        when {
            sampleSize >= 20 -> EtaConfidence.HIGH
            sampleSize >= 10 -> EtaConfidence.MEDIUM
            else -> EtaConfidence.LOW
        }
}
