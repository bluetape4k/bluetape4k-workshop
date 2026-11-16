package io.bluetape4k.workshop.commerce.metering.domain

import io.bluetape4k.money.bigDecimalValue
import io.bluetape4k.money.currencyUnitOf
import io.bluetape4k.money.moneyOf
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.javamoney.moneta.Money as MonetaMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Currency
import java.util.UUID

private const val MAX_USAGE_DECIMAL_SCALE = 6

/** Ledger persistence가 유지하는 금액의 소수점 자릿수. */
internal const val BILLING_MONEY_SCALE = 6

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

/**
 * Bluetape Moneta 금액에 usage billing의 음수 금지·통화 scale·HALF_UP 정책을 적용한다.
 * persistence와 API 경계는 기존 scalar 금액/통화 필드를 계속 사용한다.
 */
fun MonetaMoney.normalized(scale: Int = currency.defaultFractionDigits): MonetaMoney {
    bigDecimalValue.requireZeroOrPositiveNumber("money.amount")
    currency.defaultFractionDigits.requireZeroOrPositiveNumber("currency.defaultFractionDigits")
    scale.requireZeroOrPositiveNumber("money.scale")
    return moneyOf(
        bigDecimalValue.setScale(scale, RoundingMode.HALF_UP),
        currency,
    )
}

operator fun MonetaMoney.plus(other: MonetaMoney): MonetaMoney {
    require(currency == other.currency) { "currency mismatch: $currency != ${other.currency}" }
    return moneyOf(add(other).bigDecimalValue, currency).normalized()
}

fun charge(
    quantity: UsageQuantity,
    unitPrice: UnitPrice,
    currency: Currency,
    scale: Int = currency.defaultFractionDigits,
): MonetaMoney =
    moneyOf(
        quantity.value.multiply(unitPrice.value),
        currencyUnitOf(currency.currencyCode),
    ).normalized(scale)

/**
 * Moneta로 charge를 계산한 뒤 ledger의 고정 scale로 변환한다.
 * 호출자는 이미 검증된 usage quantity와 unit price를 전달해야 한다.
 */
fun chargeAmount(quantity: BigDecimal, unitPrice: BigDecimal, currencyCode: String): BigDecimal =
    charge(
        quantity = UsageQuantity(quantity),
        unitPrice = UnitPrice(unitPrice),
        currency = Currency.getInstance(currencyCode),
        scale = BILLING_MONEY_SCALE,
    ).bigDecimalValue

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
