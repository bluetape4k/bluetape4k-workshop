package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticApplicationContext

internal class EventSourcedApplicationLifecycleTest {

    @Test
    fun `startup raises readiness only after authority probe succeeds`() {
        val state = EventSourcedOperationalState()
        val workers = RecordingRuntimeWorkers()
        val lifecycle = lifecycle(state, EventSourcedStartupProbe {}, workers)

        lifecycle.start()

        state.readinessState() shouldBeEqualTo EventSourcedReadinessState.READY
        workers.started shouldBeEqualTo true
    }

    @Test
    fun `startup authority failure leaves the process running but readiness down`() {
        val state = EventSourcedOperationalState()
        val lifecycle = lifecycle(state, EventSourcedStartupProbe { error("database unavailable") })

        lifecycle.start()

        state.readinessState() shouldBeEqualTo EventSourcedReadinessState.AUTHORITY_FAILED
    }

    @Test
    fun `shutdown invokes every concrete runtime stage after readiness drops`() {
        val state = EventSourcedOperationalState()
        val workers = RecordingRuntimeWorkers()
        val lifecycle = lifecycle(state, EventSourcedStartupProbe {}, workers)
        lifecycle.start()

        lifecycle.stop()

        workers.stopped shouldBeEqualTo
            listOf("projection", "rebuild", "maintenance", "leases")
    }

    private fun lifecycle(
        state: EventSourcedOperationalState,
        probe: EventSourcedStartupProbe,
        workers: EventSourcedRuntimeWorkers = RecordingRuntimeWorkers(),
    ) =
        EventSourcedApplicationLifecycle(
            StaticApplicationContext(),
            state,
            EventSourcedDatabasePermitGate(),
            probe,
            workers,
        )
}

private class RecordingRuntimeWorkers : EventSourcedRuntimeWorkers {
    var started: Boolean = false
    val stopped = mutableListOf<String>()

    override fun start() {
        started = true
    }

    override fun stopProjection() {
        stopped += "projection"
    }

    override fun stopRebuild() {
        stopped += "rebuild"
    }

    override fun stopMaintenance() {
        stopped += "maintenance"
    }

    override fun releaseFencedLeases() {
        stopped += "leases"
    }
}
