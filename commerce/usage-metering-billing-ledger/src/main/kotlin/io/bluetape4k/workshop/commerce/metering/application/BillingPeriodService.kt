package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.checkBeFalse
import io.bluetape4k.support.requireBeFalse
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotNull
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
import java.util.*

data class CloseRunView(val id: UUID, val periodId: UUID, val state: CloseRunState, val cutoff: Instant)

@Service
class BillingPeriodService(
    private val periods: BillingPeriodRepository,
    private val properties: MeteringProperties,
    private val clock: Clock,
) {
    @Transactional
    fun create(tenantId: TenantId, currency: Currency, startsAt: Instant, endsAt: Instant): UUID {
        endsAt.requireGt(startsAt, "endsAt")

        BillingCalendars.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[BillingCalendars.tenantId] = tenantId.value
            it[BillingCalendars.currency] = currency.currencyCode
            it[createdAt] = clock.instant()
        }
        val calendar: BillingCalendarEntity = BillingCalendarEntity
            .find {
                (BillingCalendars.tenantId eq tenantId.value) and (BillingCalendars.currency eq currency.currencyCode)
            }
            .single()

        periods.overlapping(calendar.id.value, startsAt, endsAt)
            .checkBeFalse {
                "period.overlapping must be false. calendarId=${calendar.id}, " +
                    "startsAt=$startsAt endsAt=$endsAt"
            }

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
        BillingPeriods.selectAll()
            .where { BillingPeriods.id eq periodId }
            .forUpdate()
            .singleOrNull() ?: error("period_not_found")

        val period = periods.findTenant(periodId, tenantId.value)
            .requireNotNull { "BillingPeriodEntity not found. periodId=$periodId, tenantId=$tenantId" }

        period.activeCloseRunId?.let { existingId ->
            val existing = CloseRunEntity[existingId]
            return CloseRunView(
                id = existing.id.value,
                periodId = periodId,
                state = CloseRunState.valueOf(existing.state),
                cutoff = existing.cutoffReceivedAt,
            )
        }
        val now = clock.instant()
        check(period.state == BillingPeriodState.OPEN.name) { "period.state[${period.state}] not open." }
        check(now >= period.allowedLatenessDeadline) { "now[$now] must be at least ${period.allowedLatenessDeadline}" }

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

        return CloseRunView(
            id = run.id.value,
            periodId = periodId,
            state = CloseRunState.RUNNING,
            cutoff = now
        )
    }
}
