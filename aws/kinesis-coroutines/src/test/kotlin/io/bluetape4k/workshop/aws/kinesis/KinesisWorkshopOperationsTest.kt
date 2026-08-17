package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.bluetape4k.junit5.coroutines.runSuspendIO
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

class KinesisWorkshopOperationsTest {

    @Test
    fun `metrics expose only allowlisted names and tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = KinesisWorkshopMetrics(registry)

        metrics.incrementPublish("local", KinesisWorkshopMetrics.OUTCOME_SUCCESS)
        metrics.incrementConsume("local", KinesisWorkshopMetrics.OUTCOME_SUCCESS)
        metrics.incrementRetry("local", "consume")
        metrics.incrementFailure("local", "consume")

        registry.meters.map { it.id.name }.toSet() shouldBeEqualTo KinesisWorkshopMetrics.ALLOWED_NAMES
        registry.meters.forEach { meter ->
            meter.id.tags.map { it.key }.toSet() shouldBeEqualTo setOf("backend", "operation", "outcome")
        }
    }

    @Test
    fun `health local is up and real aws starts unknown then goes down on failure`() {
        val local = KinesisWorkshopHealthIndicator(KinesisWorkshopProperties())
        local.health().status shouldBeEqualTo Status.UP

        val real = KinesisWorkshopHealthIndicator(
            KinesisWorkshopProperties(profile = KinesisWorkshopProperties.REAL_AWS_PROFILE)
        )
        real.health().status shouldBeEqualTo Status.UNKNOWN
        real.markFailure()
        real.health().status shouldBeEqualTo Status.DOWN
        real.health().details.keys shouldBeEqualTo emptySet()
    }

    @Test
    fun `shutdown cancels app jobs but passively waits for caller collector`() = runSuspendIO {
        val scope = KinesisDemoScope()
        val callerJob = CoroutineScope(Dispatchers.Default).launch {
            awaitCancellation()
        }
        scope.registerCallerCollector(callerJob)
        val appJob = scope.launchDemo {
            awaitCancellation()
        }
        val shutdown = KinesisShutdownConfiguration(scope, Duration.ofMillis(25))

        shutdown.stop(Runnable {})

        shutdown.timedOut shouldBeEqualTo true
        callerJob.isActive shouldBeEqualTo true
        appJob.isActive shouldBeEqualTo false
        callerJob.cancelAndJoin()
        scope.close()
    }

    @Test
    fun `shutdown closes collector admission before draining`() = runSuspendIO {
        val scope = KinesisDemoScope()
        val callerJob = CoroutineScope(Dispatchers.Default).launch {
            awaitCancellation()
        }

        scope.closeAdmission()

        scope.registerCallerCollector(callerJob) shouldBeEqualTo false
        scope.callerCollectorCount shouldBeEqualTo 0

        callerJob.cancelAndJoin()
        scope.close()
    }

    @Test
    fun `shutdown timeout does not signal completion while caller collector is active`() = runSuspendIO {
        val scope = KinesisDemoScope()
        val callerJob = CoroutineScope(Dispatchers.Default).launch {
            awaitCancellation()
        }
        scope.registerCallerCollector(callerJob)
        val callbackCalled = AtomicBoolean(false)
        val shutdown = KinesisShutdownConfiguration(scope, Duration.ofMillis(25))

        shutdown.stop(Runnable { callbackCalled.set(true) })

        shutdown.timedOut shouldBeEqualTo true
        callbackCalled.get() shouldBeEqualTo false

        callerJob.cancelAndJoin()
        scope.close()
    }
}
