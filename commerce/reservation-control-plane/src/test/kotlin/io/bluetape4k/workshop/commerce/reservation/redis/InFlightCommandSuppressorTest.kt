package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class InFlightCommandSuppressorTest {
    @Test
    fun `redis lock hit suppresses duplicate work`() {
        val backend = RecordingSuppressionBackend(ArrayDeque(listOf(false)))
        val suppressor = InFlightCommandSuppressor(backend)
        var executions = 0

        val outcome =
            suppressor.execute("hmac-prefix-01") {
                executions++
                "unexpected"
            }

        outcome shouldBeEqualTo SuppressionOutcome.Suppressed
        executions shouldBeEqualTo 0
    }

    @Test
    fun `redis outage delegates correctness to postgres and recovers on the next command`() {
        val backend =
            RecordingSuppressionBackend(
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
    fun `acquired suppression lease is closed when command fails`() {
        val backend = RecordingSuppressionBackend(ArrayDeque(listOf(true)))
        val suppressor = InFlightCommandSuppressor(backend)

        assertFailsWith<IllegalArgumentException> {
            suppressor.execute<String>("hmac-prefix-03") { throw IllegalArgumentException("command failed") }
        }

        backend.closes shouldBeEqualTo 1
    }

    private class RecordingSuppressionBackend(
        private val acquireResults: ArrayDeque<Any>,
    ) : CommandSuppressionBackend {
        var closes = 0

        override fun tryAcquire(opaqueCommandId: String): CommandSuppressionLease? {
            val result = acquireResults.removeFirst()
            if (result is RuntimeException) throw result
            return if (result as Boolean) CommandSuppressionLease { closes++ } else null
        }
    }
}
