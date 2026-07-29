package io.bluetape4k.workshop.commerce.usagebilling.query.config

import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryRecoveryService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant

@ConfigurationProperties("usage-billing.query")
data class QueryProperties(
    val maxQuarantineBacklog: Long = 0,
    val maxOldestQuarantineAge: Duration = DEFAULT_MAX_OLDEST_QUARANTINE_AGE,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private const val DEFAULT_MAX_OLDEST_QUARANTINE_AGE_MINUTES = 15L
private val DEFAULT_MAX_OLDEST_QUARANTINE_AGE: Duration =
    Duration.ofMinutes(DEFAULT_MAX_OLDEST_QUARANTINE_AGE_MINUTES)

@Component
class QueryMetrics(
    private val registry: MeterRegistry,
) {
    fun inboxOutcome(outcome: String, eventType: String) {
        check(outcome in OUTCOMES) { "unsupported_query_inbox_outcome:$outcome" }
        check(eventType in EVENT_TYPES) { "unsupported_query_event_type:$eventType" }
        Counter.builder("usage_billing_inbox_outcome_total")
            .tag("service", "query")
            .tag("outcome", outcome)
            .tag("event_type", eventType)
            .register(registry)
            .increment()
    }

    fun redrive(outcome: String) {
        check(outcome in REDRIVE_OUTCOMES) { "unsupported_query_redrive_outcome:$outcome" }
        Counter.builder("usage_billing_redrive_total")
            .tag("service", "query")
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    private companion object {
        val OUTCOMES = setOf("APPLIED", "DUPLICATE", "DEFERRED", "QUARANTINED")
        val REDRIVE_OUTCOMES = setOf("REQUESTED", "NOT_FOUND")
        val EVENT_TYPES = setOf(
            "PriceActivated",
            "UsageAccepted",
            "UsageCorrected",
            "ChargeRated",
            "AdjustmentPosted",
            "InvoiceIssued",
            "InvoiceCorrectionIssued",
        )
    }
}

@Component("usageBillingQueryRecovery")
class QueryRecoveryHealthIndicator(
    private val recovery: QueryRecoveryService,
    private val properties: QueryProperties,
    private val clock: Clock,
) : HealthIndicator {
    override fun health(): Health {
        val snapshot = recovery.snapshot()
        val oldestAge = snapshot.oldestQuarantineAt?.let { Duration.between(it, Instant.now(clock)) }
        val degraded = snapshot.quarantineCount > properties.maxQuarantineBacklog ||
            (oldestAge != null && oldestAge > properties.maxOldestQuarantineAge)
        return (if (degraded) Health.down() else Health.up())
            .withDetail("quarantineCount", snapshot.quarantineCount)
            .withDetail("oldestQuarantineAgeSeconds", oldestAge?.seconds ?: 0)
            .build()
    }
}
