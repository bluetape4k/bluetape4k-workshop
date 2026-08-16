package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyConformanceConfig
import io.bluetape4k.junit5.http.idempotency.assertBoundedWaitHttpIdempotencyConformance
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration

class BoundedWaitHttpIdempotencyConformanceTest {

    @Test
    fun `shared coordinator fixture passes upstream bounded wait conformance`() = runBlocking {
        BoundedWaitHttpIdempotencyFixture(
            BoundedWaitHttpIdempotencyConformanceConfig(
                waitTimeout = Duration.ofSeconds(2),
                scenarioTimeout = Duration.ofSeconds(15),
                maxWaitersPerKey = 2,
                retention = Duration.ofHours(1),
                inFlightRetryAfter = Duration.ofSeconds(1),
                overflowRetryAfter = Duration.ofSeconds(2),
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
                    waitTimeout = Duration.ofSeconds(2),
                    scenarioTimeout = Duration.ofSeconds(15),
                    maxWaitersPerKey = 2,
                    retention = Duration.ofHours(1),
                    inFlightRetryAfter = Duration.ofSeconds(1),
                    overflowRetryAfter = Duration.ofSeconds(2),
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
