package io.bluetape4k.workshop.observability.basic.observation

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.micrometer.observation.coroutines.currentObservationInContext
import io.bluetape4k.micrometer.observation.coroutines.withObservationContextSuspending
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ObservationLifecycleTest {

    @Test
    fun `released helper propagates current observation across dispatcher boundary`() = runSuspendIO {
        val registry = TestObservationRegistry.create()

        withObservationContextSuspending("order.service.fetch", registry) {
            withContext(Dispatchers.Default) {
                currentObservationInContext().shouldNotBeNull()
            }
        }
    }

    @Test
    fun `released helper stops observation exactly once on success`() = runSuspendIO {
        val handler = StopCountingHandler()
        val registry = ObservationRegistry.create().also {
            it.observationConfig().observationHandler(handler)
        }

        withObservationContextSuspending("order.service.fetch", registry) { "ok" }

        handler.stopCount.get() shouldBeEqualTo 1
        handler.errorCount.get() shouldBeEqualTo 0
    }

    @Test
    fun `released helper stops observation exactly once and records failure`() = runSuspendIO {
        val handler = StopCountingHandler()
        val registry = ObservationRegistry.create().also {
            it.observationConfig().observationHandler(handler)
        }
        val expected = IllegalStateException("inventory unavailable")

        try {
            withObservationContextSuspending<String>("order.service.fetch", registry) {
                throw expected
            }
        } catch (actual: IllegalStateException) {
            actual.message shouldBeEqualTo expected.message
        }

        handler.stopCount.get() shouldBeEqualTo 1
        handler.errorCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `released helper rethrows cancellation and stops observation exactly once`() = runSuspendIO {
        val handler = StopCountingHandler()
        val registry = ObservationRegistry.create().also {
            it.observationConfig().observationHandler(handler)
        }

        val job = launch {
            withObservationContextSuspending<Unit>("order.service.fetch", registry) {
                awaitCancellation()
            }
        }
        job.cancelAndJoin()

        handler.stopCount.get() shouldBeEqualTo 1
        handler.errorCount.get() shouldBeEqualTo 0
    }

    private class StopCountingHandler : ObservationHandler<Observation.Context> {
        val stopCount = AtomicInteger()
        val errorCount = AtomicInteger()

        override fun supportsContext(context: Observation.Context): Boolean = true

        override fun onError(context: Observation.Context) {
            errorCount.incrementAndGet()
        }

        override fun onStop(context: Observation.Context) {
            stopCount.incrementAndGet()
        }
    }
}
