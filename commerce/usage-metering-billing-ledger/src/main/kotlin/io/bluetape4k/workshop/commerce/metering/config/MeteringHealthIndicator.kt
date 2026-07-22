package io.bluetape4k.workshop.commerce.metering.config

import io.bluetape4k.workshop.commerce.metering.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.domain.CommandReceiptStatus
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriods
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRuns
import io.bluetape4k.workshop.commerce.metering.persistence.CommandReceipts
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationRuns
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

private const val NO_AGE_RECORDED = -1L

@Component("meteringAuthority")
class MeteringHealthIndicator(
    private val clock: Clock,
) : HealthIndicator {
    @Transactional(readOnly = true)
    override fun health(): Health {
        val now = clock.instant()
        val oldestClosing = BillingPeriods.selectAll()
            .where { BillingPeriods.state eq BillingPeriodState.CLOSING.name }
            .orderBy(BillingPeriods.createdAt to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
        val hasUnpriced = CloseRuns.selectAll().where { CloseRuns.unpricedCount greater 0L }.limit(1).any()
        val staleReceipt = CommandReceipts.selectAll().where {
            (CommandReceipts.status eq CommandReceiptStatus.IN_PROGRESS.name) and
                (CommandReceipts.leaseDeadline less now)
        }.limit(1).any()
        val lastReconciliation = ReconciliationRuns.selectAll()
            .where { ReconciliationRuns.completedAt.isNotNull() }
            .orderBy(ReconciliationRuns.completedAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(ReconciliationRuns.completedAt)
        return Health.up()
            .withDetail(
                "oldestClosingAgeSeconds",
                oldestClosing?.let { Duration.between(it[BillingPeriods.createdAt], now).seconds }
                    ?: NO_AGE_RECORDED,
            )
            .withDetail("hasUnpricedUsage", hasUnpriced)
            .withDetail("hasStaleReceipt", staleReceipt)
            .withDetail(
                "lastReconciliationAgeSeconds",
                lastReconciliation?.let { Duration.between(it, now).seconds } ?: NO_AGE_RECORDED,
            )
            .build()
    }
}
