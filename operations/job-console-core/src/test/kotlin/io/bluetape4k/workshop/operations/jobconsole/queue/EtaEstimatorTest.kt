package io.bluetape4k.workshop.operations.jobconsole.queue

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.api.EtaConfidence
import org.junit.jupiter.api.Test
import java.time.Instant

/** ETA가 sample 부족을 숨기지 않고 p50/p90 범위와 confidence를 정직하게 계산하는지 검증한다. */
/** ETA가 sample 부족을 숨기지 않고 p50/p90 범위와 confidence를 정직하게 계산하는지 검증한다. */
class EtaEstimatorTest {

    companion object: KLogging() {
/**
 * NOW은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }

/**
 * estimator은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val estimator = EtaEstimator(minimumSampleSize = 3)

    @Test
/**
 * 이 시나리오는 "insufficient samples never fabricate a precise ETA" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `insufficient samples never fabricate a precise ETA`() {
        val estimate = estimator.estimate(emptyList(), jobsAhead = 3, now = NOW)

        estimate.confidence shouldBeEqualTo EtaConfidence.INSUFFICIENT_DATA
        estimate.startRange.shouldBeNull()
        estimate.completionRange.shouldBeNull()
    }

    @Test
/**
 * 이 시나리오는 "p50 and p90 produce an honest bounded range" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
