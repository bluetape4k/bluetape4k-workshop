package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.bucket4j.ratelimit.RateLimitDiagnostics
import io.bluetape4k.bucket4j.ratelimit.RateLimitRejectionReason
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class VoucherAdmissionGateTest {
    @Test
    fun `rate quota rejection exposes retry after without returning a consumed token`() {
        val limiter = RecordingRateLimiter(rejected())
        val gate = VoucherAdmissionGate(limiter)

        gate.decide("v1:opaque-rate-key") shouldBeEqualTo AdmissionDecision.RateLimited(Duration.ofSeconds(1))
        limiter.keys shouldBeEqualTo listOf("v1:opaque-rate-key")
        gate.state() shouldBeEqualTo AdmissionState.HEALTHY
    }

    @Test
    fun `three failures degrade and three successful probes recover with hysteresis`() {
        val clock = MutableTestClock()
        val limiter =
            RecordingRateLimiter(
                failure(), failure(), failure(),
                consumed(), consumed(), consumed(),
            )
        val gate = VoucherAdmissionGate(limiter, clock = clock)

        repeat(3) { gate.decide("v1:key") shouldBeEqualTo AdmissionDecision.Proceed }
        gate.state() shouldBeEqualTo AdmissionState.DEGRADED
        gate.decide("v1:key") shouldBeEqualTo AdmissionDecision.Proceed
        limiter.keys.size shouldBeEqualTo 3

        clock.advance(Duration.ofSeconds(1))
        gate.decide("v1:key") shouldBeEqualTo AdmissionDecision.Proceed
        gate.state() shouldBeEqualTo AdmissionState.RECOVERING
        repeat(2) { gate.decide("v1:key") shouldBeEqualTo AdmissionDecision.Proceed }
        gate.state() shouldBeEqualTo AdmissionState.HEALTHY
    }

    @Test
    fun `a recovering probe failure returns the gate to degraded`() {
        val clock = MutableTestClock()
        val limiter = RecordingRateLimiter(failure(), failure(), failure(), consumed(), failure())
        val gate = VoucherAdmissionGate(limiter, clock = clock)

        repeat(3) { gate.decide("v1:key") }
        clock.advance(Duration.ofSeconds(1))
        gate.decide("v1:key")
        gate.state() shouldBeEqualTo AdmissionState.RECOVERING
        gate.decide("v1:key")
        gate.state() shouldBeEqualTo AdmissionState.DEGRADED
    }

    @Test
    fun `degraded recovery probe is single flight while callers continue to PostgreSQL`() {
        val clock = MutableTestClock()
        val limiter = BlockingRecoveryRateLimiter()
        val gate = VoucherAdmissionGate(limiter, clock = clock)
        repeat(3) { gate.decide("v1:key") }
        clock.advance(Duration.ofSeconds(1))

        VirtualThreads.executorService().use { executor ->
            val probe = executor.submit<AdmissionDecision> { gate.decide("v1:key") }
            limiter.probeEntered.await(2, TimeUnit.SECONDS) shouldBeEqualTo true
            val followers = (1..8).map { executor.submit<AdmissionDecision> { gate.decide("v1:key") } }

            followers.forEach { it.get(2, TimeUnit.SECONDS) shouldBeEqualTo AdmissionDecision.Proceed }
            limiter.releaseProbe.countDown()
            probe.get(2, TimeUnit.SECONDS) shouldBeEqualTo AdmissionDecision.Proceed
        }

        limiter.calls.get() shouldBeEqualTo 4
        gate.state() shouldBeEqualTo AdmissionState.RECOVERING
    }

    private class RecordingRateLimiter(vararg initial: RateLimitResult) : RateLimiter<String> {
        private val results = ArrayDeque(initial.toList())
        val keys = mutableListOf<String>()

        override fun consume(
            key: String,
            numToken: Long,
        ): RateLimitResult {
            keys += key
            return results.removeFirst()
        }
    }

    private class BlockingRecoveryRateLimiter : RateLimiter<String> {
        val calls = AtomicInteger()
        val probeEntered = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)

        override fun consume(
            key: String,
            numToken: Long,
        ): RateLimitResult =
            if (calls.incrementAndGet() <= 3) {
                failure()
            } else {
                probeEntered.countDown()
                check(releaseProbe.await(2, TimeUnit.SECONDS)) { "recovery probe release timed out" }
                consumed()
            }
    }

    private class MutableTestClock(
        private var current: Instant = Instant.parse("2026-07-19T10:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private companion object {
        fun consumed(): RateLimitResult = RateLimitResult.consumed(1, 9, RateLimitDiagnostics.EMPTY)

        fun rejected(): RateLimitResult =
            RateLimitResult.rejected(
                0,
                RateLimitDiagnostics.rejected(
                    Duration.ofSeconds(1).toNanos(),
                    Duration.ofSeconds(10).toNanos(),
                    RateLimitRejectionReason.INSUFFICIENT_TOKENS,
                ),
            )

        fun failure(): RateLimitResult = RateLimitResult.error(IllegalStateException("redis unavailable"))
    }
}
