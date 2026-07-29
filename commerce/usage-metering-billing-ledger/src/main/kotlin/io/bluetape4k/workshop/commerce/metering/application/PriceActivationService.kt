package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.PriceVersion
import io.bluetape4k.workshop.commerce.metering.domain.PriceWindow
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.domain.UnitPrice
import io.bluetape4k.workshop.commerce.metering.persistence.MeterRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionEntity
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.Meters
import io.bluetape4k.workshop.commerce.metering.persistence.PricingSchedules
import io.bluetape4k.workshop.commerce.metering.persistence.PricingScheduleRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Currency

data class PriceGapRepair(
    val tenantId: TenantId,
    val meterCode: MeterCode,
    val currency: Currency,
    val unitPrice: UnitPrice,
    val effectiveFrom: Instant,
    val effectiveTo: Instant,
)

@Service
class PriceActivationService(
    private val meters: MeterRepository,
    private val schedules: PricingScheduleRepository,
    private val prices: PriceVersionRepository,
    private val clock: Clock,
) {
    @Transactional
    fun activate(
        tenantId: TenantId,
        meterCode: MeterCode,
        currency: Currency,
        unitPrice: UnitPrice,
        effectiveFrom: Instant,
    ): PriceVersion {
        val meter = requireNotNull(meters.find(tenantId.value, meterCode.value)) { "meter_not_found" }
        val schedule = schedules.getOrCreate(
            Uuid.V7.nextId(), tenantId.value, meter.id.value, currency.currencyCode, clock.instant(),
        )
        val timeline = prices.timeline(schedule.id.value)
        require(
            timeline.none { version ->
                effectiveFrom >= version.effectiveFrom &&
                    version.effectiveTo?.let { effectiveFrom < it } != false
            },
        ) {
            "price_window_overlap"
        }
        val previous = timeline.lastOrNull { it.effectiveTo == null }
        if (previous != null) {
            require(effectiveFrom > previous.effectiveFrom) { "price_backdate_requires_gap_repair" }
            previous.effectiveTo = effectiveFrom
        }
        val price = PriceVersionEntity.new(Uuid.V7.nextId()) {
            this.tenantId = tenantId.value
            this.scheduleId = EntityID(schedule.id.value, PricingSchedules)
            this.meterId = EntityID(meter.id.value, Meters)
            this.currency = currency.currencyCode
            this.unitPrice = unitPrice.value
            this.effectiveFrom = effectiveFrom
            effectiveTo = null
            createdAt = clock.instant()
        }
        return price.toDomain(meterCode)
    }

    @Transactional
    fun repairGap(command: PriceGapRepair): PriceVersion {
        require(command.effectiveTo > command.effectiveFrom) { "invalid_price_window" }
        val meter = requireNotNull(meters.find(command.tenantId.value, command.meterCode.value)) { "meter_not_found" }
        val schedule = schedules.getOrCreate(
            Uuid.V7.nextId(), command.tenantId.value, meter.id.value, command.currency.currencyCode, clock.instant(),
        )
        val timeline = prices.timeline(schedule.id.value)
        require(
            timeline.none { version ->
                version.effectiveFrom < command.effectiveTo &&
                    version.effectiveTo?.let { it > command.effectiveFrom } != false
            },
        ) { "price_window_overlap" }
        val price = PriceVersionEntity.new(Uuid.V7.nextId()) {
            tenantId = command.tenantId.value
            this.scheduleId = EntityID(schedule.id.value, PricingSchedules)
            this.meterId = EntityID(meter.id.value, Meters)
            currency = command.currency.currencyCode
            unitPrice = command.unitPrice.value
            effectiveFrom = command.effectiveFrom
            effectiveTo = command.effectiveTo
            createdAt = clock.instant()
        }
        return price.toDomain(command.meterCode)
    }

    @Transactional(readOnly = true)
    fun select(tenantId: TenantId, meterCode: MeterCode, currency: Currency, occurredAt: Instant): PriceVersion? {
        val meter = meters.find(tenantId.value, meterCode.value) ?: return null
        return prices.select(tenantId.value, meter.id.value, currency.currencyCode, occurredAt)?.toDomain(meterCode)
    }

    private fun PriceVersionEntity.toDomain(meterCode: MeterCode): PriceVersion =
        PriceVersion(
            id = id.value,
            tenantId = TenantId(tenantId),
            meterCode = meterCode,
            unitPrice = UnitPrice(unitPrice),
            currency = Currency.getInstance(currency),
            window = PriceWindow(effectiveFrom, effectiveTo),
        )
}
