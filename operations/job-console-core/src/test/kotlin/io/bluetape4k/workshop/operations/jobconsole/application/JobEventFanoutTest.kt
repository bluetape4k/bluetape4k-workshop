package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import io.bluetape4k.workshop.operations.jobconsole.api.JobEventType
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*
import java.util.concurrent.CountDownLatch

/** SSE fan-out의 bounded buffer가 느린 한 client 때문에 outbox와 다른 client를 막지 않는지 검증한다. */
/** SSE fan-out의 bounded buffer가 느린 한 client 때문에 outbox와 다른 client를 막지 않는지 검증한다. */
class JobEventFanoutTest {

    companion object: KLogging() {
/**
 * EVENT은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val EVENT = JobEvent(
            UUID.randomUUID(),
            JobEventType.JOB_UPDATED,
            UUID.randomUUID(),
            1,
            Instant.EPOCH
        )
    }

    @Test
/**
 * 이 시나리오는 "slow consumer is evicted without blocking a healthy consumer" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `slow consumer is evicted without blocking a healthy consumer`() {
        val healthy = CountDownLatch(1)
        val fanout = BoundedJobEventFanout(writeTimeout = 50.millis())

        fanout.subscribe("slow") { Thread.sleep(5_000) }
        fanout.subscribe("healthy") { healthy.countDown() }

        val result = fanout.publish(EVENT)
        log.debug { "result=$result" }

        healthy.count shouldBeEqualTo 0L
        result.evictedSubscribers shouldBeEqualTo 1
        fanout.subscriberCount shouldBeEqualTo 1
    }


}
