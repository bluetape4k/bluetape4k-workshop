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
/**
 * CloseRunView은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property periodId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 사용량 계량·청구 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property cutoff 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


data class CloseRunView(val id: UUID, val periodId: UUID, val state: CloseRunState, val cutoff: Instant)
/**
 * BillingPeriodService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property periods 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property properties 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
class BillingPeriodService(
    /**
     * `periods`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val periods: BillingPeriodRepository,
    /**
     * `properties`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: MeteringProperties,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {
    /**
     * `create`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = create(tenantId = tenantId, currency = currency, startsAt = startsAt, endsAt
     * = endsAt)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param currency 요청 경계에서 검증된 `currency`입니다.
     * @param startsAt 요청 경계에서 검증된 `startsAt`입니다.
     * @param endsAt 요청 경계에서 검증된 `endsAt`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
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

    /**
     * `startClose`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = startClose(tenantId = tenantId, periodId = UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param periodId 요청 경계에서 검증된 `periodId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
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
