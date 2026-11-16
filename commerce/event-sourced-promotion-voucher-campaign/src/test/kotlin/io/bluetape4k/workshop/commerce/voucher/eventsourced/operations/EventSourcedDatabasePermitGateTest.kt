package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.join
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

internal class EventSourcedDatabasePermitGateTest {

    private companion object: KLogging() {
        private val AWAIT_TIMEOUT_SECONDS = 5.seconds
    }

    @Test
    fun `foreground saturation rejects before JDBC admission without consuming readiness capacity`() {
        val gate = EventSourcedDatabasePermitGate(
            budget = EventSourcedDatabasePermitBudget(
                foreground = 1,
                projection = 1,
                rebuild = 1,
                maintenance = 1,
                readiness = 16,
            ),
            acquireTimeout = 1.millis(),
        )

        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        val holder: Thread = Thread.ofVirtual().start {
            runCatching {
                gate.withPermit(EventSourcedDatabaseLane.FOREGROUND) {
                    acquired.countDown()
                    release.await(AWAIT_TIMEOUT_SECONDS).shouldBeTrue()
                }
            }.onFailure(failure::set)
        }
        acquired.await(AWAIT_TIMEOUT_SECONDS).shouldBeTrue()

        try {
            assertFailsWith<DatabaseBulkheadRejected> {
                gate.withPermit(EventSourcedDatabaseLane.FOREGROUND) { error("must not be admitted") }
            }
            gate.snapshot(EventSourcedDatabaseLane.READINESS).available shouldBeEqualTo 16
            gate.withPermit(EventSourcedDatabaseLane.READINESS) {}
        } finally {
            release.countDown()
            holder.join(AWAIT_TIMEOUT_SECONDS)
        }

        failure.get().shouldBeNull()
        gate.snapshot(EventSourcedDatabaseLane.FOREGROUND).available shouldBeEqualTo 1
        gate.snapshot(EventSourcedDatabaseLane.READINESS).available shouldBeEqualTo 16
        gate.snapshot(EventSourcedDatabaseLane.FOREGROUND).rejected shouldBeEqualTo 1L
    }

    @Test
    fun `shutdown rejects new admissions only after in flight work has drained`() {
        val gate = EventSourcedDatabasePermitGate(acquireTimeout = 1.millis())

        gate.beginShutdown()

        assertFailsWith<DatabaseBulkheadRejected> {
            gate.withPermit(EventSourcedDatabaseLane.MAINTENANCE) {
                error("must not be admitted")
            }
        }
        gate.awaitDrained(Duration.ZERO).shouldBeTrue()
    }
}
