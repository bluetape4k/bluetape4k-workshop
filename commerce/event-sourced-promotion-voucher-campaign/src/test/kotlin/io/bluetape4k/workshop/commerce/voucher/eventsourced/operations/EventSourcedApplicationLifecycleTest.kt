package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticApplicationContext

internal class EventSourcedApplicationLifecycleTest {

    @Test
    fun `startup raises readiness only after authority probe succeeds`() {
        val state = EventSourcedOperationalState()
        val lifecycle = lifecycle(state, EventSourcedStartupProbe {})

        lifecycle.start()

        state.readinessState() shouldBeEqualTo EventSourcedReadinessState.READY
    }

    @Test
    fun `startup authority failure leaves the process running but readiness down`() {
        val state = EventSourcedOperationalState()
        val lifecycle = lifecycle(state, EventSourcedStartupProbe { error("database unavailable") })

        lifecycle.start()

        state.readinessState() shouldBeEqualTo EventSourcedReadinessState.AUTHORITY_FAILED
    }

    private fun lifecycle(
        state: EventSourcedOperationalState,
        probe: EventSourcedStartupProbe,
    ) =
        EventSourcedApplicationLifecycle(
            StaticApplicationContext(),
            state,
            EventSourcedDatabasePermitGate(),
            probe,
        )
}
