package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportObserver
import io.bluetape4k.leader.audit.LeaderAuditExportSnapshot
import io.bluetape4k.leader.audit.LeaderAuditExporter
import io.bluetape4k.leader.audit.LeaderAuditSubmitResult
import io.bluetape4k.workshop.leader.jobsafety.audit.InMemoryAuditHttpClient
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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

    @Test
    fun `close logs resource step and error type without exception message`() {
        val logger = LoggerFactory.getLogger(JobSafetyAuditShutdownCoordinator::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val previousLevel = logger.level
        logger.level = Level.WARN
        logger.addAppender(appender)

        val client = InMemoryAuditHttpClient()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val executor = Executors.newSingleThreadExecutor()
        val scope = JobSafetyAuditScope()
        val coordinator = JobSafetyAuditShutdownCoordinator(
            shutdownTimeout = Duration.ofSeconds(1),
            subscription = AutoCloseable { error("subscription-secret") },
            exporter = TracedExporter { },
            clientLifecycle = JobSafetyAuditHttpClientLifecycle(client),
            scheduler = scheduler,
            executor = executor,
            scope = scope,
        )

        try {
            coordinator.close()
            val messages = appender.list.map { it.formattedMessage }.joinToString("\n")
            messages shouldContain "job_safety_audit_shutdown_failed"
            messages shouldContain "resource_step=subscription.close"
            messages shouldContain "outcome=exception"
            messages shouldContain "error_type=java.lang.IllegalStateException"
            messages shouldNotContain "subscription-secret"
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
            client.close()
            scheduler.shutdownNow()
            executor.shutdownNow()
            scope.close()
        }
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
