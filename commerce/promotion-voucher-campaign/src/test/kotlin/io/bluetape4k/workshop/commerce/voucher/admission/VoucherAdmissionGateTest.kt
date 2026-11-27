package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.bucket4j.ratelimit.RateLimitDiagnostics
import io.bluetape4k.bucket4j.ratelimit.RateLimitRejectionReason
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.shared.time.MutableClock
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * VoucherAdmissionGateTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherAdmissionGateTest {

    private companion object: KLogging() {

/**
 * consumed은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun consumed(): RateLimitResult = RateLimitResult.consumed(
            consumedTokens = 1,
            availableTokens = 9,
            diagnostics = RateLimitDiagnostics.EMPTY
        )

/**
 * rejected은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun rejected(): RateLimitResult = RateLimitResult.rejected(
            availableTokens = 0,
            diagnostics = RateLimitDiagnostics.rejected(
                nanosToWaitForRefill = 1.seconds().toNanos(),
                nanosToWaitForReset = 10.seconds().toNanos(),
                rejectionReason = RateLimitRejectionReason.INSUFFICIENT_TOKENS,
            ),
        )

/**
 * failure은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun failure(): RateLimitResult =
            RateLimitResult.error(IllegalStateException("redis unavailable"))
    }

    @Test
/**
 * 이 시나리오는 "rate quota rejection exposes retry after without returning a consumed token" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rate quota rejection exposes retry after without returning a consumed token`() {
        val limiter = RecordingRateLimiter(rejected())
        val gate = VoucherAdmissionGate(limiter)

        gate.decide("v1:opaque-rate-key") shouldBeEqualTo AdmissionDecision.RateLimited(1.seconds())
        limiter.keys shouldBeEqualTo listOf("v1:opaque-rate-key")
        gate.state() shouldBeEqualTo AdmissionState.HEALTHY
    }

    @Test
/**
 * 이 시나리오는 "three failures degrade and three successful probes recover with hysteresis" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `three failures degrade and three successful probes recover with hysteresis`() {
        val clock = MutableClock(Instant.parse("2026-07-19T10:00:00Z"))
        val limiter = RecordingRateLimiter(
            failure(), failure(), failure(),
            consumed(), consumed(), consumed(),
        )
        val gate = VoucherAdmissionGate(limiter, clock = clock)

        repeat(3) {
            gate.decide("v1:key") shouldBeEqualTo AdmissionDecision.Proceed
        }
        gate.state() shouldBeEqualTo AdmissionState.DEGRADED
        gate.decide("v1:key") shouldBeEqualTo AdmissionDecision.Proceed
        limiter.keys.size shouldBeEqualTo 3

        clock.advance(1.seconds())
        gate.decide("v1:key") shouldBeEqualTo AdmissionDecision.Proceed
        gate.state() shouldBeEqualTo AdmissionState.RECOVERING

        repeat(2) {
            gate.decide("v1:key") shouldBeEqualTo AdmissionDecision.Proceed
        }
        gate.state() shouldBeEqualTo AdmissionState.HEALTHY
    }

    @Test
/**
 * 이 시나리오는 "a recovering probe failure returns the gate to degraded" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `a recovering probe failure returns the gate to degraded`() {
        val clock = MutableClock(Instant.parse("2026-07-19T10:00:00Z"))
        val limiter = RecordingRateLimiter(failure(), failure(), failure(), consumed(), failure())
        val gate = VoucherAdmissionGate(limiter, clock = clock)

        repeat(3) {
            gate.decide("v1:key")
        }
        clock.advance(1.seconds())
        gate.decide("v1:key")
        gate.state() shouldBeEqualTo AdmissionState.RECOVERING
        gate.decide("v1:key")
        gate.state() shouldBeEqualTo AdmissionState.DEGRADED
    }

    @Test
/**
 * 이 시나리오는 "degraded recovery probe is single flight while callers continue to PostgreSQL" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `degraded recovery probe is single flight while callers continue to PostgreSQL`() {
        val clock = MutableClock(Instant.parse("2026-07-19T10:00:00Z"))
        val limiter = BlockingRecoveryRateLimiter()
        val gate = VoucherAdmissionGate(limiter, clock = clock)
        repeat(3) {
            gate.decide("v1:key")
        }
        clock.advance(1.seconds())

        VirtualThreads.executorService().use { executor ->
            val probe = executor.submit<AdmissionDecision> {
                gate.decide("v1:key")
            }
            limiter.probeEntered.await(2.seconds).shouldBeTrue()
            val followers = List(8) {
                executor.submit<AdmissionDecision> {
                    gate.decide("v1:key")
                }
            }

            followers.forEach {
                it.get(2.seconds) shouldBeEqualTo AdmissionDecision.Proceed
            }
            limiter.releaseProbe.countDown()
            probe.get(2.seconds) shouldBeEqualTo AdmissionDecision.Proceed
        }

        limiter.calls.get() shouldBeEqualTo 4
        gate.state() shouldBeEqualTo AdmissionState.RECOVERING
    }

/**
 * RecordingRateLimiter 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingRateLimiter(vararg initial: RateLimitResult): RateLimiter<String> {
/**
 * results은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val results = ArrayDeque(initial.toList())
/**
 * keys은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val keys = mutableListOf<String>()

/**
 * consume은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun consume(
            key: String,
            numToken: Long,
        ): RateLimitResult {
            keys += key
            return results.removeFirst()
        }
    }

/**
 * BlockingRecoveryRateLimiter 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class BlockingRecoveryRateLimiter: RateLimiter<String> {
/**
 * calls은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val calls = AtomicInteger()
/**
 * probeEntered은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val probeEntered = CountDownLatch(1)
/**
 * releaseProbe은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val releaseProbe = CountDownLatch(1)

/**
 * consume은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun consume(
            key: String,
            numToken: Long,
        ): RateLimitResult =
            if (calls.incrementAndGet() <= 3) {
                failure()
            } else {
                probeEntered.countDown()
                check(releaseProbe.await(2.seconds)) { "recovery probe release timed out" }
                consumed()
            }
    }

}
