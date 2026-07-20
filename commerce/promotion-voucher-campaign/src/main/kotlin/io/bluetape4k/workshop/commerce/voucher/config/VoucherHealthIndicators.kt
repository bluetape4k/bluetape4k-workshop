package io.bluetape4k.workshop.commerce.voucher.config

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

internal val VOUCHER_DEGRADED_STATUS = Status("DEGRADED")

internal enum class VoucherDegradedComponent {
    REDIS,
    LEADER,
}

/** Tracks advisory backend failures without allowing them to replace PostgreSQL authority. */
@Component
internal class VoucherDegradationState(
    private val metrics: VoucherMetrics? = null,
) {
    private val degraded = ConcurrentHashMap.newKeySet<VoucherDegradedComponent>()

    fun degrade(component: VoucherDegradedComponent) {
        if (degraded.add(component) && component == VoucherDegradedComponent.REDIS) {
            metrics?.redisDegraded("REDIS")
        }
    }

    fun recover(component: VoucherDegradedComponent) {
        degraded -= component
    }

    fun isDegraded(component: VoucherDegradedComponent): Boolean = component in degraded
}

/** Readiness authority: a process is ready only while PostgreSQL accepts connections. */
@Component("voucherDatabaseHealthIndicator")
internal class VoucherDatabaseHealthIndicator(
    private val dataSource: DataSource,
) : HealthIndicator {
    override fun health(): Health =
        try {
            val valid = dataSource.connection.use { it.isValid(VALIDATION_TIMEOUT_SECONDS) }
            if (valid) Health.up().build() else Health.down().build()
        } catch (_: Exception) {
            Health.down().build()
        }

    private companion object {
        private const val VALIDATION_TIMEOUT_SECONDS = 1
    }
}

/** Redis remains advisory; loss is visible as DEGRADED while HTTP health stays successful. */
@Component("redisHealthIndicator")
internal class VoucherRedisHealthIndicator(
    private val state: VoucherDegradationState,
) : HealthIndicator {
    override fun health(): Health = state.health(VoucherDegradedComponent.REDIS)
}

/** Leader election loss pauses scheduled work but does not remove PostgreSQL readiness. */
@Component("leaderHealthIndicator")
internal class VoucherLeaderHealthIndicator(
    private val state: VoucherDegradationState,
) : HealthIndicator {
    override fun health(): Health = state.health(VoucherDegradedComponent.LEADER)
}

private fun VoucherDegradationState.health(component: VoucherDegradedComponent): Health =
    if (isDegraded(component)) Health.status(VOUCHER_DEGRADED_STATUS).build() else Health.up().build()
