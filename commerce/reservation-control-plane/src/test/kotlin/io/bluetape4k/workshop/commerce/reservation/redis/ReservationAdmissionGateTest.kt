package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

class ReservationAdmissionGateTest {

    @Test
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
    fun `redis outage falls back to the always on local bulkhead and later recovers`() {
        val backend = RecordingAdmissionBackend(
            acquireResults = ArrayDeque(listOf(IllegalStateException("redis secret detail"), true)),
        )
        val gate = gate(backend)

        val degraded = gate.execute { "postgres-result" }
        val recovered = gate.execute { "postgres-result" }

        degraded shouldBeEqualTo AdmissionOutcome.Executed("postgres-result", AdmissionMode.LOCAL_FALLBACK)
        recovered shouldBeEqualTo AdmissionOutcome.Executed("postgres-result", AdmissionMode.REDIS_ADVISORY)
        backend.releases shouldBeEqualTo 1
    }

    @Test
    fun `acquired redis permit is released when database action fails`() {
        val backend = RecordingAdmissionBackend(acquireResults = ArrayDeque(listOf(true)))
        val gate = gate(backend)

        assertFailsWith<IllegalArgumentException> {
            gate.execute<String> { throw IllegalArgumentException("database failed") }
        }

        backend.releases shouldBeEqualTo 1
    }

    private fun gate(backend: AdmissionPermitBackend) = ReservationAdmissionGate(
        localBulkhead = NodeLocalDatabaseBulkhead(
            foregroundPermits = 1,
            backgroundPermits = 1,
            acquireTimeout = Duration.ZERO,
        ),
        redisBackend = backend,
        redisWaitTime = Duration.ofMillis(100),
    )

    private class RecordingAdmissionBackend(
        private val acquireResults: ArrayDeque<Any>,
    ) : AdmissionPermitBackend {
        var releases = 0

        override fun tryAcquire(waitTime: Duration): Boolean {
            val result = acquireResults.removeFirst()
            if (result is RuntimeException) throw result
            return result as Boolean
        }

        override fun release() {
            releases++
        }
    }
}
