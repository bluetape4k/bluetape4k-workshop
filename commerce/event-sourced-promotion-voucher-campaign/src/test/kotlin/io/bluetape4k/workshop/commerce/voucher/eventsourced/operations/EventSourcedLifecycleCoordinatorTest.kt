package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import java.time.Duration

internal class EventSourcedLifecycleCoordinatorTest {

    @Test
    fun `shutdown refuses readiness before stopping workers draining permits and releasing leases`() {
        val state = EventSourcedOperationalState().also(EventSourcedOperationalState::markReady)
        val coordinator =
            EventSourcedLifecycleCoordinator(
                state = state,
                gate = EventSourcedDatabasePermitGate(),
                workers = EventSourcedWorkerShutdown({}, {}, {}, {}),
                graceDeadline = Duration.ofMillis(10),
            )

        coordinator.shutdown()

        coordinator.events() shouldBeEqualTo
            listOf(
                EventSourcedShutdownEvent.READINESS_DOWN,
                EventSourcedShutdownEvent.REJECT_ADMISSION,
                EventSourcedShutdownEvent.STOP_PROJECTION,
                EventSourcedShutdownEvent.STOP_REBUILD,
                EventSourcedShutdownEvent.STOP_MAINTENANCE,
                EventSourcedShutdownEvent.AWAIT_DRAIN,
                EventSourcedShutdownEvent.RELEASE_FENCED_LEASES,
            )
        state.readinessState() shouldBeEqualTo EventSourcedReadinessState.STOPPING
        coordinator.forcedDrain().shouldBeFalse()
    }
}
