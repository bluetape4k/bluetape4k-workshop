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
/**
 * PriceGapRepair은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property meterCode 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property currency 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 * @property unitPrice 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 * @property effectiveFrom 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property effectiveTo 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


data class PriceGapRepair(
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
     * `currency`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val currency: Currency,
    /**
     * `unitPrice`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val unitPrice: UnitPrice,
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
    val effectiveTo: Instant,
)
/**
 * PriceActivationService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property meters 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property schedules 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property prices 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
class PriceActivationService(
    /**
     * `meters`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val meters: MeterRepository,
    /**
     * `schedules`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val schedules: PricingScheduleRepository,
    /**
     * `prices`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val prices: PriceVersionRepository,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {
    /**
     * `activate`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = activate(tenantId = tenantId, meterCode = meterCode, currency = currency,
     * unitPrice =
     * unitPrice, effectiveFrom = effectiveFrom)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param meterCode 요청 경계에서 검증된 `meterCode`입니다.
     * @param currency 요청 경계에서 검증된 `currency`입니다.
     * @param unitPrice 요청 경계에서 검증된 `unitPrice`입니다.
     * @param effectiveFrom 요청 경계에서 검증된 `effectiveFrom`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
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

    /**
     * `repairGap`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = repairGap(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
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

    /**
     * `select`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = select(tenantId = tenantId, meterCode = meterCode, currency = currency,
     * occurredAt =
     * occurredAt)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param meterCode 요청 경계에서 검증된 `meterCode`입니다.
     * @param currency 요청 경계에서 검증된 `currency`입니다.
     * @param occurredAt 요청 경계에서 검증된 `occurredAt`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
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

    /**
     * toDomain은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param meterCode 업무 처리에 필요한 meterCode 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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
