package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.domain.CloseRunState
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.persistence.BillingCalendarEntity
import io.bluetape4k.workshop.commerce.metering.persistence.BillingCalendars
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodEntity
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodRepository
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriods
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRunEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Currency
import java.util.UUID

data class CloseRunView(val id: UUID, val periodId: UUID, val state: CloseRunState, val cutoff: Instant)

@Service
class BillingPeriodService(
    private val periods: BillingPeriodRepository,
    private val properties: MeteringProperties,
    private val clock: Clock,
) {
    @Transactional
    fun create(tenantId: TenantId, currency: Currency, startsAt: Instant, endsAt: Instant): UUID {
        require(endsAt > startsAt) { "invalid_billing_period" }
        BillingCalendars.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[BillingCalendars.tenantId] = tenantId.value
            it[BillingCalendars.currency] = currency.currencyCode
            it[createdAt] = clock.instant()
        }
        BillingCalendars.selectAll().where {
            (BillingCalendars.tenantId eq tenantId.value) and (BillingCalendars.currency eq currency.currencyCode)
        }.forUpdate().single()
        val calendar = BillingCalendarEntity.find {
            (BillingCalendars.tenantId eq tenantId.value) and (BillingCalendars.currency eq currency.currencyCode)
        }.single()
        require(!periods.overlapping(calendar.id.value, startsAt, endsAt)) { "billing_period_overlap" }
        return BillingPeriodEntity.new(Uuid.V7.nextId()) {
            this.tenantId = tenantId.value
            calendarId = EntityID(calendar.id.value, BillingCalendars)
            this.currency = currency.currencyCode
            this.startsAt = startsAt
            this.endsAt = endsAt
            allowedLatenessDeadline = endsAt.plus(properties.allowedLateness)
            state = BillingPeriodState.OPEN.name
            version = 0L
            activeCloseRunId = null
            cutoffReceivedAt = null
            finalizedAt = null
            invoiceId = null
            createdAt = clock.instant()
        }.id.value
    }

    @Transactional
    fun startClose(tenantId: TenantId, periodId: UUID): CloseRunView {
        BillingPeriods.selectAll().where { BillingPeriods.id eq periodId }.forUpdate().singleOrNull()
            ?: error("period_not_found")
        val period = requireNotNull(periods.findTenant(periodId, tenantId.value)) { "period_not_found" }
        period.activeCloseRunId?.let { existingId ->
            val existing = CloseRunEntity[existingId]
            return CloseRunView(
                existing.id.value,
                periodId,
                CloseRunState.valueOf(existing.state),
                existing.cutoffReceivedAt,
            )
        }
        val now = clock.instant()
        require(period.state == BillingPeriodState.OPEN.name) { "period_not_open" }
        require(now >= period.allowedLatenessDeadline) { "allowed_lateness_not_elapsed" }
        val run = CloseRunEntity.new(Uuid.V7.nextId()) {
            this.tenantId = tenantId.value
            this.periodId = EntityID(period.id.value, BillingPeriods)
            cutoffReceivedAt = now
            state = CloseRunState.RUNNING.name
            lastOccurredAt = null
            lastUsageEventId = null
            scannedCount = 0L
            pricedCount = 0L
            unpricedCount = 0L
            checkpointVersion = 0L
            lastErrorCategory = null
            createdAt = now
            updatedAt = now
        }
        period.state = BillingPeriodState.CLOSING.name
        period.activeCloseRunId = run.id.value
        period.cutoffReceivedAt = now
        period.version += 1
        return CloseRunView(run.id.value, periodId, CloseRunState.RUNNING, now)
    }
}
