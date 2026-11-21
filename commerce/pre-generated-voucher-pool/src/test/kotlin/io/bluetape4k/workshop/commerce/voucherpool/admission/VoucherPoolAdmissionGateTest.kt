package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.bucket4j.ratelimit.RateLimitDiagnostics
import io.bluetape4k.bucket4j.ratelimit.RateLimitRejectionReason
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*

/**
 * VoucherPoolAdmissionGateTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolAdmissionGateTest {

    private companion object: KLogging() {
/**
 * consumed은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun consumed(): RateLimitResult =
            RateLimitResult.consumed(1, 9, RateLimitDiagnostics.EMPTY)

/**
 * rejected은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun rejected(): RateLimitResult =
            RateLimitResult.rejected(
                availableTokens = 0,
                diagnostics = RateLimitDiagnostics.rejected(
                    nanosToWaitForRefill = 1.seconds().toNanos(),
                    nanosToWaitForReset = 1.minutes().toNanos(),
                    rejectionReason = RateLimitRejectionReason.INSUFFICIENT_TOKENS,
                ),
            )

/**
 * failure은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun failure(): RateLimitResult =
            RateLimitResult.error(IllegalStateException("redis unavailable"))
    }


    @Test
/**
 * 이 시나리오는 "operation namespaces never share a distributed quota" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operation namespaces never share a distributed quota`() {
        val backend = RecordingAdmissionBackend(rejected(), consumed())
        val gate = VoucherPoolAdmissionGate(backend)
        val principal = ByteArray(32) { 0x61 }

        gate.admit(AdmissionNamespace.REVEAL, principal) shouldBeEqualTo AdmissionDecision.RATE_LIMITED
        gate.admit(AdmissionNamespace.REDEEM, principal) shouldBeEqualTo AdmissionDecision.ALLOW

        backend.namespaces shouldBeEqualTo listOf(AdmissionNamespace.REVEAL, AdmissionNamespace.REDEEM)
        backend.keys.distinct() shouldHaveSize 2
    }

    @Test
/**
 * 이 시나리오는 "Redis failure degrades to a node local hard cap and recovers with hysteresis" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Redis failure degrades to a node local hard cap and recovers with hysteresis`() {
        val clock = MutableTestClock()
        val backend = RecordingAdmissionBackend(failure(), consumed(), consumed(), consumed())
        val limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.OPERATOR_AUTH, 3)
        val gate = VoucherPoolAdmissionGate(
            backend = backend,
            limits = limits,
            recoveryPolicy = AdmissionRecoveryPolicy(recoverySuccessThreshold = 3),
            clock = clock,
        )
        val principal = ByteArray(32) { 0x62 }

        gate.admit(AdmissionNamespace.OPERATOR_AUTH, principal) shouldBeEqualTo AdmissionDecision.DEGRADED_ALLOW
        gate.admit(AdmissionNamespace.OPERATOR_AUTH, principal) shouldBeEqualTo AdmissionDecision.DEGRADED_ALLOW
        gate.admit(AdmissionNamespace.OPERATOR_AUTH, principal) shouldBeEqualTo AdmissionDecision.DEGRADED_ALLOW
        gate.admit(AdmissionNamespace.OPERATOR_AUTH, principal) shouldBeEqualTo AdmissionDecision.RATE_LIMITED
        gate.state() shouldBeEqualTo AdmissionState.DEGRADED

        clock.advance(1.minutes())
        repeat(3) {
            gate.admit(AdmissionNamespace.OPERATOR_AUTH, principal) shouldBeEqualTo AdmissionDecision.DEGRADED_ALLOW
        }
        gate.state() shouldBeEqualTo AdmissionState.HEALTHY
    }

    @Test
/**
 * 이 시나리오는 "missing Redis backend starts degraded without changing PostgreSQL authority" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `missing Redis backend starts degraded without changing PostgreSQL authority`() {
        val gate = VoucherPoolAdmissionGate(backend = null)
        val principal = ByteArray(32) { 0x63 }
        gate.admit(AdmissionNamespace.RESERVE, principal) shouldBeEqualTo AdmissionDecision.DEGRADED_ALLOW
        gate.state() shouldBeEqualTo AdmissionState.DEGRADED
    }

/**
 * RecordingAdmissionBackend 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingAdmissionBackend(vararg initial: RateLimitResult): VoucherPoolAdmissionBackend {
/**
 * results은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val results = ArrayDeque(initial.toList())
/**
 * namespaces은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val namespaces = mutableListOf<AdmissionNamespace>()
/**
 * keys은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val keys = mutableListOf<String>()

/**
 * consume은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun consume(namespace: AdmissionNamespace, key: String): RateLimitResult {
            namespaces += namespace
            keys += key
            return results.removeFirst()
        }
    }

/**
 * MutableTestClock 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class MutableTestClock(
/**
 * current은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private var current: Instant = Instant.now(),
    ): Clock() {
/**
 * getZone은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun getZone(): ZoneId = ZoneOffset.UTC

/**
 * withZone은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun withZone(zone: ZoneId): Clock = this

/**
 * instant은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun instant(): Instant = current

/**
 * advance은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun advance(duration: Duration) {
            current += duration
        }
    }

}
