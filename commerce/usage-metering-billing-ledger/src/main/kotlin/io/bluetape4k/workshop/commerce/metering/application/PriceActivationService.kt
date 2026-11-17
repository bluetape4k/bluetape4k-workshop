package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.PriceVersion
import io.bluetape4k.workshop.commerce.metering.domain.PriceWindow
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.domain.UnitPrice
import io.bluetape4k.workshop.commerce.metering.persistence.MeterRepository
import io.bluetape4k.workshop.commerce.metering.persistence.Meters
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionEntity
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersions.effectiveFrom
import io.bluetape4k.workshop.commerce.metering.persistence.PricingScheduleRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PricingSchedules
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.*

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
        val meter = meters.find(tenantId.value, meterCode.value)
            .requireNotNull { "Meter not found. tenantId=${tenantId.value}, meterCode=${meterCode.value}" }

        val schedule = schedules.getOrCreate(
            id = Uuid.V7.nextId(),
            tenantId = tenantId.value,
            meterId = meter.id.value,
            currency = currency.currencyCode,
            now = clock.instant(),
        )
        val timeline = prices.timeline(schedule.id.value)

        timeline.none { version ->
            effectiveFrom >= version.effectiveFrom && version.effectiveTo?.let { effectiveFrom < it } != false
        }.checkBeTrue { "prices window must be overlap" }

        val previous = timeline.lastOrNull { it.effectiveTo == null }
        if (previous != null) {
            check(effectiveFrom > previous.effectiveFrom) {
                "effectiveFrom must be less than previous. " +
                    "effectiveFrom=$effectiveFrom, previous.effectiveTo=$previous.effectiveTo"
            }
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
        require(command.effectiveTo > command.effectiveFrom) {
            "effectiveTo must be greater than effectiveFrom. " +
                "effectiveTo=${command.effectiveTo}, effectiveFrom=${command.effectiveFrom}"
        }
        val meter = meters.find(command.tenantId.value, command.meterCode.value)
            .requireNotNull {
                "meter not found. tenantId=${command.tenantId.value}, " +
                    "meterCode=${command.meterCode.value}"
            }

        val schedule = schedules.getOrCreate(
            id = Uuid.V7.nextId(),
            tenantId = command.tenantId.value,
            meterId = meter.id.value,
            currency = command.currency.currencyCode,
            now = clock.instant(),
        )
        val timeline = prices.timeline(schedule.id.value)
        timeline.none { version ->
            version.effectiveFrom < command.effectiveTo &&
                version.effectiveTo?.let { it > command.effectiveFrom } != false
        }.checkBeTrue { "prices window must be overlap" }

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
    fun select(
        tenantId: TenantId,
        meterCode: MeterCode,
        currency: Currency,
        occurredAt: Instant,
    ): PriceVersion? {
        val meter = meters.find(tenantId.value, meterCode.value) ?: return null
        return prices.select(
            tenantId = tenantId.value,
            meterId = meter.id.value,
            currency = currency.currencyCode,
            occurredAt = occurredAt
        )?.toDomain(meterCode)
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
