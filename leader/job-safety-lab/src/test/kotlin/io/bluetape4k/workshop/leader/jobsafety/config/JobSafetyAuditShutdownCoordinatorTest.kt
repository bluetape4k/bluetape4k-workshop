package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportObserver
import io.bluetape4k.leader.audit.LeaderAuditExportSnapshot
import io.bluetape4k.leader.audit.LeaderAuditExporter
import io.bluetape4k.leader.audit.LeaderAuditSubmitResult
import io.bluetape4k.workshop.leader.jobsafety.audit.InMemoryAuditHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor

internal class JobSafetyAuditShutdownCoordinatorTest {

    @Test
    fun `close is ordered idempotent and bounded across all owned resources`() {
        val trace = mutableListOf<String>()
        var subscriptionCloses = 0
        val subscription = AutoCloseable { subscriptionCloses++ }
        val exporter = TracedExporter { }
        val client = InMemoryAuditHttpClient()
        val clientLifecycle = JobSafetyAuditHttpClientLifecycle(client)
        val scheduler = ScheduledThreadPoolExecutor(1).apply {
            removeOnCancelPolicy = true
            schedule({ Thread.sleep(10_000) }, 1, java.util.concurrent.TimeUnit.MINUTES)
        }
        val executor = Executors.newSingleThreadExecutor()
        val scope = JobSafetyAuditScope()
        val coordinator = JobSafetyAuditShutdownCoordinator(
            shutdownTimeout = Duration.ofMillis(500),
            subscription = subscription,
            exporter = exporter,
            clientLifecycle = clientLifecycle,
            scheduler = scheduler,
            executor = executor,
            scope = scope,
            onStep = { trace += it },
        )

        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            coordinator.close()
            coordinator.close()
        }

        trace shouldBeEqualTo listOf(
            "subscription.close",
            "exporter.close",
            "client.shutdownNow",
            "client.awaitTermination",
            "scheduler.shutdownNow",
            "scheduler.awaitTermination",
            "executor.shutdownNow",
            "executor.awaitTermination",
            "scope.close",
        )
        subscriptionCloses shouldBeEqualTo 1
        exporter.closes shouldBeEqualTo 1
        scheduler.queue.shouldBeEmpty()
        scheduler.isTerminated.shouldBeTrue()
        executor.isTerminated.shouldBeTrue()
        client.isTerminated.shouldBeTrue()
        scope.isActive.shouldBeFalse()
    }

    private class TracedExporter(private val onClose: () -> Unit) : LeaderAuditExporter {
        var closes: Int = 0

        override fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult =
            LeaderAuditSubmitResult.DROPPED_CLOSED

        override fun observe(observer: LeaderAuditExportObserver): AutoCloseable = AutoCloseable { }

        override fun snapshot(): LeaderAuditExportSnapshot = error("not used")

        override fun close() {
            closes++
            onClose()
        }
    }
}
