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

/**
 * `MAX_USAGE_DECIMAL_SCALE`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MAX_USAGE_DECIMAL_SCALE = 6

/** Ledger persistence가 유지하는 금액의 소수점 자릿수. */
/** Ledger persistence가 유지하는 금액의 소수점 자릿수. */
internal const val BILLING_MONEY_SCALE = 6
/**
 * UsageQuantity은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property value 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@JvmInline
value class UsageQuantity(val value: BigDecimal) {
    init {
        value.requirePositiveNumber("quantity")
        value.scale().requireInRange(0, MAX_USAGE_DECIMAL_SCALE, "quantity.scale")
    }
}
/**
 * UnitPrice은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property value 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


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

/**
 * plus은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param other 업무 처리에 필요한 other 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
operator fun MonetaMoney.plus(other: MonetaMoney): MonetaMoney {
    require(currency == other.currency) { "currency mismatch: $currency != ${other.currency}" }
    return moneyOf(add(other).bigDecimalValue, currency).normalized()
}

/**
 * charge은 사용량 계량·청구의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param quantity 업무 처리에 필요한 quantity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param unitPrice 업무 처리에 필요한 unitPrice 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param currency 업무 처리에 필요한 currency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param scale 업무 처리에 필요한 scale 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
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
/**
 * PriceWindow은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property effectiveFrom 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property effectiveTo 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


data class PriceWindow(
    /**
     * `effectiveFrom`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val effectiveFrom: Instant,
    /**
     * `effectiveTo`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val effectiveTo: Instant?,
) {
    init {
        require(effectiveTo == null || effectiveTo > effectiveFrom) {
            "effectiveTo must be later than effectiveFrom"
        }
    }

    /**
     * contains은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param instant 업무 처리에 필요한 instant 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    operator fun contains(instant: Instant): Boolean =
        instant >= effectiveFrom && (effectiveTo == null || instant < effectiveTo)
}
/**
 * PriceVersion은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property tenantId 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property meterCode 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property unitPrice 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 * @property currency 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 * @property window 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


data class PriceVersion(
    /**
     * `id`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: UUID,
    /**
     * `tenantId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: TenantId,
    /**
     * `meterCode`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val meterCode: MeterCode,
    /**
     * `unitPrice`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val unitPrice: UnitPrice,
    /**
     * `currency`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val currency: Currency,
    /**
     * `window`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val window: PriceWindow,
)
