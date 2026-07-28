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

/** advisory backend failure를 추적하되 PostgreSQL authority를 대체하지 못하게 합니다. */
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

/** readiness authority입니다. PostgreSQL이 connection을 받을 때만 process가 ready입니다. */
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

/** Redis는 advisory로 유지됩니다. 손실은 DEGRADED로 표시하지만 HTTP health는 성공으로 유지합니다. */
@Component("redisHealthIndicator")
internal class VoucherRedisHealthIndicator(
    private val state: VoucherDegradationState,
) : HealthIndicator {
    override fun health(): Health = state.health(VoucherDegradedComponent.REDIS)
}

/** leader election 손실은 scheduled work를 일시 중지하지만 PostgreSQL readiness를 제거하지 않습니다. */
@Component("leaderHealthIndicator")
internal class VoucherLeaderHealthIndicator(
    private val state: VoucherDegradationState,
) : HealthIndicator {
    override fun health(): Health = state.health(VoucherDegradedComponent.LEADER)
}

private fun VoucherDegradationState.health(component: VoucherDegradedComponent): Health =
    if (isDegraded(component)) Health.status(VOUCHER_DEGRADED_STATUS).build() else Health.up().build()
