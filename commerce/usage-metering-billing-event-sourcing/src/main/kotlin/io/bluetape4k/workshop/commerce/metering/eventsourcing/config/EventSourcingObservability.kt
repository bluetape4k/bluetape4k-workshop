package io.bluetape4k.workshop.commerce.metering.eventsourcing.config

import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.BillingTelemetry
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionFailureRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionTelemetry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class EventSourcingMetrics(
    private val registry: MeterRegistry,
) : BillingTelemetry, ProjectionTelemetry {
    fun recordAppend(outcome: String, duration: Duration) {
        timer("billing.event.append", "outcome", outcome).record(duration)
    }

    override fun recordReplay(outcome: String, eventCount: Int, duration: Duration) {
        registry.counter("billing.event.replay", "outcome", outcome).increment()
        registry.summary("billing.event.replay.events", "outcome", outcome).record(eventCount.toDouble())
        timer("billing.event.replay.duration", "outcome", outcome).record(duration)
    }

    override fun recordSnapshotFallback(reason: String) {
        registry.counter("billing.snapshot.fallback", "reason", reason).increment()
    }

    fun recordProjection(outcome: String, lag: Long) {
        registry.counter("billing.projection.batch", "outcome", outcome).increment()
        registry.summary("billing.projection.lag", "outcome", outcome).record(lag.toDouble())
    }

    override fun recordRebuild(outcome: String) {
        registry.counter("billing.projection.rebuild", "outcome", outcome).increment()
    }

    fun recordQuarantine(eventType: String) {
        registry.counter("billing.projection.quarantine", "event.type", eventType).increment()
    }

    override fun recordCloseBatch(outcome: String, size: Int) {
        registry.counter("billing.close.batch", "outcome", outcome).increment()
        registry.summary("billing.close.batch.size", "outcome", outcome).record(size.toDouble())
    }

    override fun recordReconciliation(kind: String) {
        registry.counter("billing.reconciliation.finding", "kind", kind).increment()
    }

    private fun timer(name: String, tagName: String, tagValue: String): Timer =
        registry.timer(name, tagName, tagValue)

    private fun Timer.record(duration: Duration) {
        record(duration.toNanos(), TimeUnit.NANOSECONDS)
    }
}

@Component
class EventSourcingHealthIndicator(
    private val eventStore: EventStoreRepository,
    private val generations: ProjectionGenerationRepository,
    private val failures: ProjectionFailureRepository,
    transactionManager: PlatformTransactionManager,
) : HealthIndicator {
    private val transactions = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    override fun health(): Health = runCatching {
        checkNotNull(transactions.execute { calculateHealth() })
    }.getOrElse { failure ->
        Health.down().withDetail("reason", "event_store_unavailable")
            .withException(failure)
            .build()
    }

    private fun calculateHealth(): Health {
        eventStore.loadAfterGlobalPosition(0, CONNECTIVITY_PAGE_SIZE)
        val active = generations.active(BILLING_PROJECTION)
            ?: return Health.down().withDetail("reason", "active_projection_missing").build()
        val lag = (active.highWatermark - active.checkpoint).coerceAtLeast(0)
        val failure = failures.latest(BILLING_PROJECTION, active.generation)
        val builder = if (failure == null) Health.up() else Health.down()
        return builder
            .withDetail("activeGeneration", active.generation)
            .withDetail("projectionPosition", active.checkpoint)
            .withDetail("projectionLag", lag)
            .withDetail("projectionState", active.state.name)
            .withDetail("quarantined", failure != null)
            .apply {
                failure?.let {
                    withDetail("failedPosition", it.globalPosition)
                    withDetail("failureAttempts", it.attemptCount)
                }
            }
            .build()
    }

    private companion object {
        const val BILLING_PROJECTION = "billing"
        const val CONNECTIVITY_PAGE_SIZE = 1
    }
}
