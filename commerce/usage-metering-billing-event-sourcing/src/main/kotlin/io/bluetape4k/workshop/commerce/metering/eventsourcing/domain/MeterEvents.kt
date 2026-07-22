package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

import java.math.BigDecimal
import java.time.Instant

sealed interface MeterEvent : DomainEvent

data class MeterRegistered(
    val meterCode: String,
    val unit: String,
    val currency: String,
) : MeterEvent {
    override val eventType: String = "meter.registered"
    override val schemaVersion: Int = 1

    init {
        require(meterCode.isNotBlank()) { "meter_code_invalid" }
        require(unit.isNotBlank()) { "meter_unit_invalid" }
        require(currency.length == CURRENCY_CODE_LENGTH) { "currency_invalid" }
    }
}

data class PriceActivated(
    val currency: String,
    val unitPrice: BigDecimal,
    val effectiveFrom: Instant,
) : MeterEvent {
    override val eventType: String = "price.activated"
    override val schemaVersion: Int = 1

    init {
        require(currency.length == CURRENCY_CODE_LENGTH) { "currency_invalid" }
        require(unitPrice.signum() >= 0) { "unit_price_invalid" }
    }
}

data class PricePoint(
    val currency: String,
    val unitPrice: BigDecimal,
    val effectiveFrom: Instant,
)

sealed interface MeterState {
    data object Empty : MeterState

    data class Active(
        val meterCode: String,
        val unit: String,
        val currency: String,
        val prices: List<PricePoint>,
    ) : MeterState
}

fun MeterState.priceAt(occurredAt: Instant): PricePoint =
    when (this) {
        MeterState.Empty -> error("meter_not_registered")
        is MeterState.Active -> prices.lastOrNull { !it.effectiveFrom.isAfter(occurredAt) }
            ?: error("price_not_found")
    }

private const val CURRENCY_CODE_LENGTH = 3
