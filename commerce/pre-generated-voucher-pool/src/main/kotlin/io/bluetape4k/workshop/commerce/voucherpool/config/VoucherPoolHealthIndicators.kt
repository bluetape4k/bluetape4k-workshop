package io.bluetape4k.workshop.commerce.voucherpool.config

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

internal val VOUCHER_POOL_DEGRADED_STATUS = Status("DEGRADED")
internal val VOUCHER_POOL_RECOVERING_STATUS = Status("RECOVERING")

internal enum class VoucherPoolHealthComponent(val authoritative: Boolean) {
    POSTGRESQL(true),
    MIGRATION(true),
    REFERENCED_KEYS(true),
    LIFECYCLE(true),
    QUARANTINE(false),
    REDIS(false),
    LEADER(false),
    RECOVERY(false),
}

internal enum class VoucherPoolHealthReason {
    HEALTHY,
    POSTGRESQL_UNAVAILABLE,
    MIGRATION_UNAVAILABLE,
    REFERENCED_KEY_UNAVAILABLE,
    SHUTTING_DOWN,
    QUARANTINED_ENTRY,
    REDIS_UNAVAILABLE,
    LEADER_UNAVAILABLE,
    RECOVERY_IN_PROGRESS,
}

internal enum class VoucherPoolHealthLevel {
    UP,
    RECOVERING,
    DEGRADED,
    DOWN,
}

internal class VoucherPoolHealthSnapshot(
    val level: VoucherPoolHealthLevel,
    val reason: VoucherPoolHealthReason,
    val lastSuccessAt: Instant,
    val lastErrorAt: Instant?,
)

/** Keeps health reasons allowlisted and independent from exception messages or tenant data. */
@Component
internal class VoucherPoolHealthState(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val states = ConcurrentHashMap<VoucherPoolHealthComponent, ComponentState>()

    @Volatile
    private var lastSuccessAt = Instant.now(clock)

    @Volatile
    private var lastErrorAt: Instant? = null

    fun fail(component: VoucherPoolHealthComponent, reason: VoucherPoolHealthReason) {
        require(component.authoritative) { "$component is advisory and cannot make readiness DOWN" }
        update(component, VoucherPoolHealthLevel.DOWN, reason)
    }

    fun degrade(component: VoucherPoolHealthComponent, reason: VoucherPoolHealthReason) {
        require(!component.authoritative) { "$component is authoritative and cannot be merely DEGRADED" }
        update(component, VoucherPoolHealthLevel.DEGRADED, reason)
    }

    fun recovering(reason: VoucherPoolHealthReason = VoucherPoolHealthReason.RECOVERY_IN_PROGRESS) {
        update(VoucherPoolHealthComponent.RECOVERY, VoucherPoolHealthLevel.RECOVERING, reason)
    }

    fun recover(component: VoucherPoolHealthComponent) {
        states -= component
        lastSuccessAt = Instant.now(clock)
    }

    fun snapshot(): VoucherPoolHealthSnapshot {
        val current = states.values.maxWithOrNull(
            compareBy<ComponentState> { it.level.ordinal }.thenBy { it.reason.ordinal },
        )
        return VoucherPoolHealthSnapshot(
            level = current?.level ?: VoucherPoolHealthLevel.UP,
            reason = current?.reason ?: VoucherPoolHealthReason.HEALTHY,
            lastSuccessAt = lastSuccessAt,
            lastErrorAt = lastErrorAt,
        )
    }

    private fun update(
        component: VoucherPoolHealthComponent,
        level: VoucherPoolHealthLevel,
        reason: VoucherPoolHealthReason,
    ) {
        require(reason != VoucherPoolHealthReason.HEALTHY) { "failure reason must be explicit" }
        val now = Instant.now(clock)
        states[component] = ComponentState(level, reason)
        lastErrorAt = now
    }

    private class ComponentState(
        val level: VoucherPoolHealthLevel,
        val reason: VoucherPoolHealthReason,
    )
}

/** PostgreSQL plus required migration/key state are the readiness authority. */
@Component("voucherPoolReadinessHealthIndicator")
internal class VoucherPoolReadinessHealthIndicator(
    private val dataSource: DataSource,
    private val state: VoucherPoolHealthState,
) : HealthIndicator {
    override fun health(): Health {
        validatePostgres()
        val snapshot = state.snapshot()
        val builder =
            when (snapshot.level) {
                VoucherPoolHealthLevel.UP -> Health.up()
                VoucherPoolHealthLevel.RECOVERING -> Health.status(VOUCHER_POOL_RECOVERING_STATUS)
                VoucherPoolHealthLevel.DEGRADED -> Health.status(VOUCHER_POOL_DEGRADED_STATUS)
                VoucherPoolHealthLevel.DOWN -> Health.down()
            }
        builder.withDetail("reason", snapshot.reason.name)
            .withDetail("lastSuccessAt", snapshot.lastSuccessAt.toString())
        snapshot.lastErrorAt?.let { builder.withDetail("lastErrorAt", it.toString()) }
        return builder.build()
    }

    private fun validatePostgres() {
        val valid =
            try {
                dataSource.connection.use { it.isValid(VALIDATION_TIMEOUT_SECONDS) }
            } catch (_: Exception) {
                false
            }
        if (valid) {
            state.recover(VoucherPoolHealthComponent.POSTGRESQL)
        } else {
            state.fail(VoucherPoolHealthComponent.POSTGRESQL, VoucherPoolHealthReason.POSTGRESQL_UNAVAILABLE)
        }
    }

    private companion object {
        const val VALIDATION_TIMEOUT_SECONDS = 1
    }
}

/** Liveness is process-only; dependency degradation belongs to readiness. */
@Component("voucherPoolLivenessHealthIndicator")
internal class VoucherPoolLivenessHealthIndicator : HealthIndicator {
    override fun health(): Health = Health.up().withDetail("reason", "PROCESS_RUNNING").build()
}
