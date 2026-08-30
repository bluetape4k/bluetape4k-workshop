package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLeaseExtensionContext
import io.bluetape4k.leader.LeaderLeaseExtensionEvent
import io.bluetape4k.leader.LeaderLeaseExtensionExecution
import io.bluetape4k.leader.LeaderLeaseExtensionSource
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderLeaseExtensionObserver
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

internal class JobSafetyLeaseExtensionObservationRegistrationTest {
    @Test
    fun `disabled registration does not publish global lease observations`() {
        val handler = CollectingObservationHandler()
        val registry = ObservationRegistry.create().also {
            it.observationConfig().observationHandler(handler)
        }
        val registration =
            JobSafetyLeaseExtensionObservation(
                registry = registry,
                options = LeaderObservationOptions(),
                enabled = false,
            )

        try {
            LockExtender.extendActiveLockDetailed(1.seconds)
            Thread.sleep(50)
            handler.stopped.size shouldBeEqualTo 0
        } finally {
            registration.close()
            registration.close()
        }
    }

    @Test
    fun `enabled registration is global and close is idempotent`() {
        val handler = CollectingObservationHandler()
        val registry = ObservationRegistry.create().also {
            it.observationConfig().observationHandler(handler)
        }
        val registration =
            JobSafetyLeaseExtensionObservation(
                registry = registry,
                options = LeaderObservationOptions(),
            )

        try {
            LockExtender.extendActiveLockDetailed(1.seconds)
            await.atMost(5.seconds).untilAsserted {
                handler.stopped.size shouldBeEqualTo 1
                handler.stopped.single().name shouldBeEqualTo "bluetape4k.leader.lease.extension"
            }

            registration.close()
            registration.close()
            val before = handler.stopped.size
            LockExtender.extendActiveLockDetailed(1.seconds)
            Thread.sleep(50)
            handler.stopped.size shouldBeEqualTo before
            registration.isClosed.shouldBeTrue()
        } finally {
            registration.close()
        }
    }

    @Test
    fun `lease observer redacts identity unless explicitly enabled`() {
        val handler = CollectingObservationHandler()
        val registry = ObservationRegistry.create().also {
            it.observationConfig().observationHandler(handler)
        }
        val event =
            LeaderLeaseExtensionEvent(
                source = LeaderLeaseExtensionSource.USER,
                execution = LeaderLeaseExtensionExecution.BLOCKING,
                outcome = ExtendOutcome.Extended(Instant.EPOCH),
                elapsedNanos = 7L,
                context = LeaderLeaseExtensionContext("secret-lock", "secret-leader"),
            )

        MicrometerObservationLeaderLeaseExtensionObserver(registry).onExtension(event)
        handler.stopped.single().high.keys.none { it == "lock.name" || it == "leader.id" }.shouldBeTrue()

        handler.stopped.clear()
        MicrometerObservationLeaderLeaseExtensionObserver(
            registry,
            LeaderObservationOptions(includeLockName = true, includeLeaderId = true),
        ).onExtension(event)
        val optedIn = handler.stopped.single()
        optedIn.high["lock.name"] shouldBeEqualTo "redacted-lock"
        optedIn.high["leader.id"] shouldBeEqualTo "redacted-leader"
    }

    @Test
    fun `lease observer includes backend exception only when enabled`() {
        val handler = CollectingObservationHandler()
        val registry = ObservationRegistry.create().also {
            it.observationConfig().observationHandler(handler)
        }
        val failure = IllegalArgumentException("secret message")
        val event =
            LeaderLeaseExtensionEvent(
                source = LeaderLeaseExtensionSource.USER,
                execution = LeaderLeaseExtensionExecution.BLOCKING,
                outcome = ExtendOutcome.BackendError(failure),
                elapsedNanos = 11L,
                context = null,
            )

        MicrometerObservationLeaderLeaseExtensionObserver(registry).onExtension(event)
        handler.stopped.single().error.shouldBeNull()

        handler.stopped.clear()
        MicrometerObservationLeaderLeaseExtensionObserver(
            registry,
            LeaderObservationOptions(includeExceptionDetails = true),
        ).onExtension(event)
        handler.stopped.single().error shouldBeEqualTo failure
    }

    private class CollectingObservationHandler : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<Snapshot>()

        override fun supportsContext(context: Observation.Context): Boolean = true

        override fun onStop(context: Observation.Context) {
            stopped +=
                Snapshot(
                    name = context.name.orEmpty(),
                    low = context.lowCardinalityKeyValues.associate { it.key to it.value },
                    high = context.highCardinalityKeyValues.associate { it.key to it.value },
                    error = context.error,
                )
        }
    }

    private data class Snapshot(
        val name: String,
        val low: Map<String, String> = emptyMap(),
        val high: Map<String, String> = emptyMap(),
        val error: Throwable? = null,
    )
}
