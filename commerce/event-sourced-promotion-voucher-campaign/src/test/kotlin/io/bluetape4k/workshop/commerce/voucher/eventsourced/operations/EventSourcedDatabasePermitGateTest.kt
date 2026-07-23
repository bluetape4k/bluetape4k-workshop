package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class EventSourcedDatabasePermitGateTest {

    @Test
    fun `foreground saturation rejects before JDBC admission without consuming readiness capacity`() {
        val gate =
            EventSourcedDatabasePermitGate(
                budget =
                    EventSourcedDatabasePermitBudget(
                        foreground = 1,
                        projection = 1,
                        rebuild = 1,
                        maintenance = 1,
                        readiness = 16,
                    ),
                acquireTimeout = Duration.ofMillis(1),
            )

        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val holder =
            Thread.ofVirtual().start {
                runCatching {
                    gate.withPermit(EventSourcedDatabaseLane.FOREGROUND) {
                        acquired.countDown()
                        release.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).shouldBeTrue()
                    }
                }.onFailure(failure::set)
            }
        acquired.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).shouldBeTrue()
        try {
            assertFailsWith<DatabaseBulkheadRejected> {
                gate.withPermit(EventSourcedDatabaseLane.FOREGROUND) { error("must not be admitted") }
            }
            gate.snapshot(EventSourcedDatabaseLane.READINESS).available shouldBeEqualTo 16
            gate.withPermit(EventSourcedDatabaseLane.READINESS) {
            }
        } finally {
            release.countDown()
            holder.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS))
        }

        failure.get() shouldBeEqualTo null
        gate.snapshot(EventSourcedDatabaseLane.FOREGROUND).available shouldBeEqualTo 1
        gate.snapshot(EventSourcedDatabaseLane.READINESS).available shouldBeEqualTo 16
        gate.snapshot(EventSourcedDatabaseLane.FOREGROUND).rejected shouldBeEqualTo 1L
    }

    @Test
    fun `shutdown rejects new admissions only after in flight work has drained`() {
        val gate = EventSourcedDatabasePermitGate(acquireTimeout = Duration.ofMillis(1))

        gate.beginShutdown()

        assertFailsWith<DatabaseBulkheadRejected> {
            gate.withPermit(EventSourcedDatabaseLane.MAINTENANCE) { error("must not be admitted") }
        }
        gate.awaitDrained(Duration.ZERO).shouldBeTrue()
    }

    private companion object {
        private const val AWAIT_TIMEOUT_SECONDS = 5L
    }
}
