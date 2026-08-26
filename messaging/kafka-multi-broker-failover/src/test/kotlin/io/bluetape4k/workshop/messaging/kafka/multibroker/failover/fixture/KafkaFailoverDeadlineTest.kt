package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KafkaFailoverDeadlineTest {

    @Test
    fun `remaining time is the minimum of module scenario and phase deadlines`() {
        val now = AtomicLong(1_000L)
        val module = KafkaFailoverDeadline.fromNow(420.seconds, now::get)
        val scenario = module.child(180.seconds)
        val phase = scenario.child(45.seconds)

        phase.remainingNanos() shouldBeEqualTo 45_000_000_000L

        now.set(10_000L)
        val afterProgress = phase.remainingNanos()
        (afterProgress > 0L).shouldBeTrue()
        (afterProgress < 45_000_000_000L).shouldBeTrue()
        (afterProgress >= 0L).shouldBeTrue()
    }

    @Test
    fun `blocking await fails with phase when the cumulative deadline expires`() {
        val now = AtomicLong(0L)
        val deadline = KafkaFailoverDeadline.fromNow(1.milliseconds, now::get)
        now.set(2_000_000L)

        val error = assertFailsWith<TimeoutException> {
            deadline.awaitBlocking("startup") { "unreachable" }
        }

        error.message?.contains("phase=startup").shouldBeTrue()
    }

    @Test
    fun `standard budgets are fixed for module and scenarios`() {
        KafkaFailoverDeadline.MODULE_TIMEOUT shouldBeEqualTo 420.seconds
        KafkaFailoverDeadline.SCENARIO_TIMEOUT shouldBeEqualTo 180.seconds
        KafkaFailoverDeadline.STARTUP_TIMEOUT shouldBeEqualTo 45.seconds
        KafkaFailoverDeadline.CLEANUP_TIMEOUT shouldBeEqualTo 10.seconds
    }
}
