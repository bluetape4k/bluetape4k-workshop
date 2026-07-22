package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.bucket4j.ratelimit.RateLimitDiagnostics
import io.bluetape4k.bucket4j.ratelimit.RateLimitRejectionReason
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque

internal class VoucherPoolAdmissionGateTest {
    @Test
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
    fun `Redis failure degrades to a node local hard cap and recovers with hysteresis`() {
        val clock = MutableTestClock()
        val backend = RecordingAdmissionBackend(failure(), consumed(), consumed(), consumed())
        val limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.OPERATOR_AUTH, 3)
        val gate =
            VoucherPoolAdmissionGate(
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

        clock.advance(Duration.ofMinutes(1))
        repeat(3) {
            gate.admit(AdmissionNamespace.OPERATOR_AUTH, principal) shouldBeEqualTo AdmissionDecision.DEGRADED_ALLOW
        }
        gate.state() shouldBeEqualTo AdmissionState.HEALTHY
    }

    @Test
    fun `missing Redis backend starts degraded without changing PostgreSQL authority`() {
        val gate = VoucherPoolAdmissionGate(backend = null)

        gate.admit(AdmissionNamespace.RESERVE, ByteArray(32) { 0x63 }) shouldBeEqualTo
            AdmissionDecision.DEGRADED_ALLOW
        gate.state() shouldBeEqualTo AdmissionState.DEGRADED
    }

    private class RecordingAdmissionBackend(vararg initial: RateLimitResult) : VoucherPoolAdmissionBackend {
        private val results = ArrayDeque(initial.toList())
        val namespaces = mutableListOf<AdmissionNamespace>()
        val keys = mutableListOf<String>()

        override fun consume(namespace: AdmissionNamespace, key: String): RateLimitResult {
            namespaces += namespace
            keys += key
            return results.removeFirst()
        }
    }

    private class MutableTestClock(
        private var current: Instant = Instant.parse("2026-07-21T00:00:00Z"),
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
                    Duration.ofMinutes(1).toNanos(),
                    RateLimitRejectionReason.INSUFFICIENT_TOKENS,
                ),
            )

        fun failure(): RateLimitResult = RateLimitResult.error(IllegalStateException("redis unavailable"))
    }
}
