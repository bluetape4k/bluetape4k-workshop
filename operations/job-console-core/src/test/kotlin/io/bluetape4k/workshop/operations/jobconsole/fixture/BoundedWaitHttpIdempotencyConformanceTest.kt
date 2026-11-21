package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.coroutines.runSuspendDefault
import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyConformanceConfig
import io.bluetape4k.junit5.http.idempotency.assertBoundedWaitHttpIdempotencyConformance
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/** Spring/Ktor가 공유하는 bounded-wait owner·replay·timeout 계약을 black-box fixture로 고정한다. */
/** Spring/Ktor가 공유하는 bounded-wait owner·replay·timeout 계약을 black-box fixture로 고정한다. */
class BoundedWaitHttpIdempotencyConformanceTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "shared coordinator fixture passes upstream bounded wait conformance" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
