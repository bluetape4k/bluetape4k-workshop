package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import io.bluetape4k.workshop.operations.jobconsole.api.JobEventType
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch

class JobEventFanoutTest {
    @Test
    fun `slow consumer is evicted without blocking a healthy consumer`() {
        val healthy = CountDownLatch(1)
        val fanout = BoundedJobEventFanout(writeTimeout = Duration.ofMillis(50))
        fanout.subscribe("slow") { Thread.sleep(5_000) }
        fanout.subscribe("healthy") { healthy.countDown() }

        val result = fanout.publish(EVENT)

        healthy.count shouldBeEqualTo 0L
        result.evictedSubscribers shouldBeEqualTo 1
        fanout.subscriberCount shouldBeEqualTo 1
    }

    companion object {
        private val EVENT = JobEvent(UUID.randomUUID(), JobEventType.JOB_UPDATED, UUID.randomUUID(), 1, Instant.EPOCH)
    }
}
