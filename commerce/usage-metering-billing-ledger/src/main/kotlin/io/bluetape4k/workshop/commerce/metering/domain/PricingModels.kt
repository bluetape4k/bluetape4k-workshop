package io.bluetape4k.workshop.commerce.metering.domain

import io.bluetape4k.money.bigDecimalValue
import io.bluetape4k.money.currencyUnitOf
import io.bluetape4k.money.moneyOf
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Currency
import java.util.UUID

private const val MAX_USAGE_DECIMAL_SCALE = 6

@JvmInline
value class UsageQuantity(val value: BigDecimal) {
    init {
        value.requirePositiveNumber("quantity")
        value.scale().requireInRange(0, MAX_USAGE_DECIMAL_SCALE, "quantity.scale")
    }
}

@JvmInline
value class UnitPrice(val value: BigDecimal) {
    init {
        value.requirePositiveNumber("unitPrice")
        value.scale().requireInRange(0, MAX_USAGE_DECIMAL_SCALE, "unitPrice.scale")
    }
}

data class Money(
    val amount: BigDecimal,
    val currency: Currency,
) {
    init {
        amount.requireZeroOrPositiveNumber("money.amount")
        currency.defaultFractionDigits.requireZeroOrPositiveNumber("currency.defaultFractionDigits")
        moneyOf(amount, currencyUnitOf(currency.currencyCode))
    }

    fun normalized(): Money =
        moneyOf(
            amount.setScale(currency.defaultFractionDigits, RoundingMode.HALF_UP),
            currencyUnitOf(currency.currencyCode),
        ).let { Money(it.bigDecimalValue, currency) }

    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "currency mismatch: $currency != ${other.currency}" }
        val total = moneyOf(amount, currencyUnitOf(currency.currencyCode))
            .add(moneyOf(other.amount, currencyUnitOf(other.currency.currencyCode)))
        return Money(total.bigDecimalValue, currency).normalized()
    }
}

fun charge(quantity: UsageQuantity, unitPrice: UnitPrice, currency: Currency): Money =
    Money(quantity.value.multiply(unitPrice.value), currency).normalized()

data class PriceWindow(
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
) {
    init {
        require(effectiveTo == null || effectiveTo > effectiveFrom) {
            "effectiveTo must be later than effectiveFrom"
        }
    }

    operator fun contains(instant: Instant): Boolean =
        instant >= effectiveFrom && (effectiveTo == null || instant < effectiveTo)
}

data class PriceVersion(
    val id: UUID,
    val tenantId: TenantId,
    val meterCode: MeterCode,
    val unitPrice: UnitPrice,
    val currency: Currency,
    val window: PriceWindow,
)
