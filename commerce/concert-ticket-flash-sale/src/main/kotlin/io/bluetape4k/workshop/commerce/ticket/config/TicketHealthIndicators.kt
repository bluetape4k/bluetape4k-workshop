package io.bluetape4k.workshop.commerce.ticket.config

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

class TicketMigrationHealthIndicator(private val readiness: TicketMigrationReadiness) : HealthIndicator {
    override fun health(): Health = if (readiness.isReady) Health.up().build() else Health.outOfService().build()
}

fun interface TicketRedisHealthProbe { fun ping(): String }

class TicketRedisHealthIndicator(private val probe: TicketRedisHealthProbe) : HealthIndicator {
    override fun health(): Health = runCatching { probe.ping() }
        .fold(
            onSuccess = { Health.up().build() },
            onFailure = { Health.outOfService().withDetail("code", "redis_unavailable").build() },
        )
}

/** Liveness deliberately excludes Redis and downstream providers. */
class TicketLivenessHealthIndicator : HealthIndicator {
    override fun health(): Health = Health.up().build()
}
