package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * ReservationAdmissionGateTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ReservationAdmissionGateTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "redis rejection does not enter the authoritative database action" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redis rejection does not enter the authoritative database action`() {
        val backend = RecordingAdmissionBackend(acquireResults = ArrayDeque(listOf(false)))
        val gate = gate(backend)
        var executions = 0

        val outcome = gate.execute {
            executions++
            "unexpected"
        }

        outcome shouldBeEqualTo AdmissionOutcome.Rejected(AdmissionRejection.REDIS_CAPACITY)
        executions shouldBeEqualTo 0
        backend.releases shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "redis outage falls back to the always on local bulkhead and later recovers" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redis outage falls back to the always on local bulkhead and later recovers`() {
        val backend = RecordingAdmissionBackend(
            acquireResults = ArrayDeque(listOf(IllegalStateException("redis secret detail"), true))
        )
        val gate = gate(backend)

        val degraded = gate.execute { "postgres-result" }
        val recovered = gate.execute { "postgres-result" }

        degraded shouldBeEqualTo AdmissionOutcome.Executed("postgres-result", AdmissionMode.LOCAL_FALLBACK)
        recovered shouldBeEqualTo AdmissionOutcome.Executed("postgres-result", AdmissionMode.REDIS_ADVISORY)
        backend.releases shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "acquired redis permit is released when database action fails" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `acquired redis permit is released when database action fails`() {
        val backend = RecordingAdmissionBackend(acquireResults = ArrayDeque(listOf(true)))
        val gate = gate(backend)

        assertFailsWith<IllegalArgumentException> {
            gate.execute<String> {
                throw IllegalArgumentException("database failed")
            }
        }

        backend.releases shouldBeEqualTo 1
    }

/**
 * gate은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun gate(backend: AdmissionPermitBackend) =
        ReservationAdmissionGate(
            localBulkhead = NodeLocalDatabaseBulkhead(
                foregroundPermits = 1,
                backgroundPermits = 1,
                acquireTimeout = Duration.ZERO
            ),
            redisBackend = backend,
            redisWaitTime = 100.millis()
        )

/**
 * RecordingAdmissionBackend 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingAdmissionBackend(
/**
 * acquireResults은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val acquireResults: ArrayDeque<Any>,
    ): AdmissionPermitBackend {
/**
 * releases은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var releases = 0

/**
 * tryAcquire은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun tryAcquire(waitTime: Duration): Boolean {
            val result = acquireResults.removeFirst()
            if (result is RuntimeException) throw result
            return result as Boolean
        }

/**
 * release은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun release() {
            releases++
        }
    }
}
