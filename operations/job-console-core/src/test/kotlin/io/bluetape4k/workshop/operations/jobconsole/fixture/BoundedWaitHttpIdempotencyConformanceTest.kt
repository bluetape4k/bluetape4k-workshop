package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.coroutines.runSuspendDefault
import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyConformanceConfig
import io.bluetape4k.junit5.http.idempotency.assertBoundedWaitHttpIdempotencyConformance
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class BoundedWaitHttpIdempotencyConformanceTest {

    companion object: KLogging()

    @Test
    fun `shared coordinator fixture passes upstream bounded wait conformance`() = runSuspendDefault {
        BoundedWaitHttpIdempotencyFixture(
            BoundedWaitHttpIdempotencyConformanceConfig(
                waitTimeout = 2.seconds(),
                scenarioTimeout = 15.seconds(),
                maxWaitersPerKey = 2,
                retention = 1.hours(),
                inFlightRetryAfter = 1.seconds(),
                overflowRetryAfter = 2.seconds(),
                maxIdempotencyKeyBytes = 255,
                maxRequestBodyBytes = 64 * 1024,
                maxReplayBodyBytes = 64 * 1024,
                maxReplayHeaderNames = 8,
                maxReplayValuesPerHeader = 4,
                maxReplayHeaderValueBytes = 4 * 1024,
                maxReplayHeaderBytes = 16 * 1024,
            ),
        ).use { fixture ->
            assertBoundedWaitHttpIdempotencyConformance(
                fixture,
                BoundedWaitHttpIdempotencyConformanceConfig(
                    waitTimeout = 2.seconds(),
                    scenarioTimeout = 15.seconds(),
                    maxWaitersPerKey = 2,
                    retention = 1.hours(),
                    inFlightRetryAfter = 1.seconds(),
                    overflowRetryAfter = 2.seconds(),
                    maxIdempotencyKeyBytes = 255,
                    maxRequestBodyBytes = 64 * 1024,
                    maxReplayBodyBytes = 64 * 1024,
                    maxReplayHeaderNames = 8,
                    maxReplayValuesPerHeader = 4,
                    maxReplayHeaderValueBytes = 4 * 1024,
                    maxReplayHeaderBytes = 16 * 1024,
                ),
            )
        }
    }
}
