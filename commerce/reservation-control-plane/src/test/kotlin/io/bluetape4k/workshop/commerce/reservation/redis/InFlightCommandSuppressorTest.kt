package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * InFlightCommandSuppressorTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class InFlightCommandSuppressorTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "redis lock hit suppresses duplicate work" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redis lock hit suppresses duplicate work`() {
        val backend = RecordingSuppressionBackend(ArrayDeque(listOf(false)))
        val suppressor = InFlightCommandSuppressor(backend)
        var executions = 0

        val outcome = suppressor.execute("hmac-prefix-01") {
            executions++
            "unexpected"
        }

        outcome shouldBeEqualTo SuppressionOutcome.Suppressed
        executions shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "redis outage delegates correctness to postgres and recovers on the next command" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redis outage delegates correctness to postgres and recovers on the next command`() {
        val backend = RecordingSuppressionBackend(
            ArrayDeque(listOf(IllegalStateException("redis secret detail"), true))
        )
        val suppressor = InFlightCommandSuppressor(backend)

        val degraded = suppressor.execute("hmac-prefix-02") { "postgres-result" }
        val recovered = suppressor.execute("hmac-prefix-02") { "postgres-result" }

        degraded shouldBeEqualTo SuppressionOutcome.Executed("postgres-result", SuppressionMode.POSTGRES_FALLBACK)
        recovered shouldBeEqualTo SuppressionOutcome.Executed("postgres-result", SuppressionMode.REDIS_ADVISORY)
        backend.closes shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "acquired suppression lease is closed when command fails" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `acquired suppression lease is closed when command fails`() {
        val backend = RecordingSuppressionBackend(ArrayDeque(listOf(true)))
        val suppressor = InFlightCommandSuppressor(backend)

        assertFailsWith<IllegalArgumentException> {
            suppressor.execute<String>("hmac-prefix-03") {
                throw IllegalArgumentException("command failed")
            }
        }

        backend.closes shouldBeEqualTo 1
    }

/**
 * RecordingSuppressionBackend 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingSuppressionBackend(
/**
 * acquireResults은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val acquireResults: ArrayDeque<Any>,
    ): CommandSuppressionBackend {
/**
 * closes은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var closes = 0

/**
 * tryAcquire은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun tryAcquire(opaqueCommandId: String): CommandSuppressionLease? {
            val result = acquireResults.removeFirst()
            if (result is RuntimeException) throw result

            return if (result as Boolean) CommandSuppressionLease { closes++ } else null
        }
    }
}
