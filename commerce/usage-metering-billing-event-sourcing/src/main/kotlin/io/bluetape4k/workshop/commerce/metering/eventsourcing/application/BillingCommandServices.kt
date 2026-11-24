package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AggregateReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingCloseStarted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodOpened
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventMetadata
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.InvoiceIssued
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.InvoiceReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.InvoiceState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PriceActivated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.ReplayedAggregate
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.AggregateReplayRequest
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.AggregateReplayer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.ReplayPolicy
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.ReplayTelemetry
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.*
/**
 * MeterCommandService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property eventStore 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property codec 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


@Service
class MeterCommandService(
    /**
     * `eventStore`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventStore: EventStore,
    /**
     * `codec`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val codec: DomainEventJsonCodec,
    metrics: ReplayTelemetry? = null,
) {

    companion object: KLogging()
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */

    private val replayRuntime = ReplayRuntime(eventStore, codec, metrics)

    /**
     * `register`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = register(tenantId = "tenant-a", meterCode = "demo", unit = "demo", currency =
     * "demo",
     * now = now)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param meterCode 요청 경계에서 검증된 `meterCode`입니다.
     * @param unit 요청 경계에서 검증된 `unit`입니다.
     * @param currency 요청 경계에서 검증된 `currency`입니다.
     * @param now 요청 경계에서 검증된 `now`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun register(
        tenantId: String,
        meterCode: String,
        unit: String,
        currency: String,
        now: Instant,
    ) {
        register(
            MeterRegistration(
                tenantId = tenantId,
                meterCode = meterCode,
                unit = unit,
                currency = currency,
                occurredAt = now
            ),
        )
    }

    /**
     * `register`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = register(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun register(command: MeterRegistration) {
        val stream = StreamKey(command.tenantId, "Meter", command.meterCode)
        val current = replay(stream, MeterState.Empty, MeterReducer, replayRuntime)

        check(current.state == MeterState.Empty) { "meter_already_registered" }

        val domainEvent = MeterRegistered(command.meterCode, command.unit, command.currency)
        val event = command.metadata
            ?.let { codec.encode(domainEvent, command.occurredAt, metadata = it) }
            ?: codec.encode(domainEvent, command.occurredAt)

        eventStore.append(stream, current.streamVersion, listOf(event))
    }

    /**
     * `activatePrice`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = activatePrice(tenantId = "tenant-a", meterCode = "demo", unitPrice =
     * unitPrice,
     * currency = "demo", effectiveFrom = effectiveFrom)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param meterCode 요청 경계에서 검증된 `meterCode`입니다.
     * @param unitPrice 요청 경계에서 검증된 `unitPrice`입니다.
     * @param currency 요청 경계에서 검증된 `currency`입니다.
     * @param effectiveFrom 요청 경계에서 검증된 `effectiveFrom`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun activatePrice(
        tenantId: String,
        meterCode: String,
        unitPrice: BigDecimal,
        currency: String,
        effectiveFrom: Instant,
    ) {
        val stream = StreamKey(tenantId, "Meter", meterCode)
        val current = replay(stream, MeterState.Empty, MeterReducer, replayRuntime)
        val event = PriceActivated(currency, unitPrice, effectiveFrom)
        MeterReducer.evolve(current.state, event)
        eventStore.append(
            stream = stream,
            expectedVersion = current.streamVersion,
            events = listOf(codec.encode(event, effectiveFrom))
        )
    }
}
/**
 * MeterRegistration은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property meterCode 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property unit 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property currency 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 * @property occurredAt 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property metadata 사용량 계량·청구 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 */


data class MeterRegistration(
    /**
     * `tenantId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `meterCode`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val meterCode: String,
    /**
     * `unit`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val unit: String,
    /**
     * `currency`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val currency: String,
    /**
     * `occurredAt`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val occurredAt: Instant,
    /**
     * `metadata`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val metadata: EventMetadata? = null,
)
/**
 * UsageAcceptance은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property created 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property stream 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property streamVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


data class UsageAcceptance(val created: Boolean, val stream: StreamKey, val streamVersion: Long)
/**
 * UsageCommandService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property eventStore 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property codec 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


@Service
class UsageCommandService(
    /**
     * `eventStore`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventStore: EventStore,
    /**
     * `codec`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val codec: DomainEventJsonCodec,
    metrics: ReplayTelemetry? = null,
) {

    companion object: KLogging()
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */

    private val replayRuntime = ReplayRuntime(eventStore, codec, metrics)

    /**
     * `accept`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = accept(tenantId = "tenant-a", usage = usage, now = now)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param usage 요청 경계에서 검증된 `usage`입니다.
     * @param now 요청 경계에서 검증된 `now`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun accept(tenantId: String, usage: UsageAccepted, now: Instant): UsageAcceptance {
        val stream = StreamKey(tenantId, "Usage", deterministicUsageId(usage.sourceSystem, usage.sourceEventId))
        val current = replay(stream, UsageState.Empty, UsageReducer, replayRuntime)

        if (current.state is UsageState.Accepted) {
            return existing(current.state, usage, stream, current.streamVersion)
        }

        return try {
            val appended = eventStore
                .append(
                    stream = stream,
                    expectedVersion = 0,
                    events = listOf(codec.encode(usage, now))
                )
                .single()
            UsageAcceptance(true, stream, appended.streamVersion)
        } catch (_: OptimisticConcurrencyException) {
            val winner = replay(stream, UsageState.Empty, UsageReducer, replayRuntime)
            existing(winner.state as UsageState.Accepted, usage, stream, winner.streamVersion)
        }
    }

    /**
     * existing은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param accepted 업무 처리에 필요한 accepted 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param requested 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun existing(
        accepted: UsageState.Accepted,
        requested: UsageAccepted,
        stream: StreamKey,
        version: Long,
    ): UsageAcceptance {
        check(accepted.usage == requested) { "usage_source_conflict" }
        return UsageAcceptance(false, stream, version)
    }

    /**
     * deterministicUsageId은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param sourceSystem 업무 처리에 필요한 sourceSystem 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param sourceEventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun deterministicUsageId(sourceSystem: String, sourceEventId: String): String =
        Digesters.SHA256.digestHexUtf8("$sourceSystem\u0000$sourceEventId")
}
/**
 * BillingLifecycleCommandService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property eventStore 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property codec 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


@Service
class BillingLifecycleCommandService(
    /**
     * `eventStore`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventStore: EventStore,
    /**
     * `codec`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val codec: DomainEventJsonCodec,
    metrics: ReplayTelemetry? = null,
) {

    companion object: KLogging()
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */

    private val replayRuntime = ReplayRuntime(eventStore, codec, metrics)

    /**
     * `open`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = open(tenantId = "tenant-a", periodId = "demo", currency = "demo", startsAt =
     * startsAt, endsAt = endsAt)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param periodId 요청 경계에서 검증된 `periodId`입니다.
     * @param currency 요청 경계에서 검증된 `currency`입니다.
     * @param startsAt 요청 경계에서 검증된 `startsAt`입니다.
     * @param endsAt 요청 경계에서 검증된 `endsAt`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun open(tenantId: String, periodId: String, currency: String, startsAt: Instant, endsAt: Instant) {
        require(startsAt < endsAt) { "billing_period_range_invalid" }

        val stream = StreamKey(tenantId, "BillingPeriod", periodId)
        val current = replay(
            stream = stream,
            initialState = BillingPeriodState.Empty,
            reducer = BillingPeriodReducer,
            runtime = replayRuntime
        )

        check(current.state == BillingPeriodState.Empty) { "billing_period_already_opened" }
        eventStore.append(
            stream = stream,
            expectedVersion = 0,
            events = listOf(codec.encode(BillingPeriodOpened(currency, startsAt, endsAt), startsAt))
        )
    }

    /**
     * `startClose`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = startClose(tenantId = "tenant-a", periodId = "demo", cutoff = cutoff)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param periodId 요청 경계에서 검증된 `periodId`입니다.
     * @param cutoff 요청 경계에서 검증된 `cutoff`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun startClose(tenantId: String, periodId: String, cutoff: Instant) {
        val stream = StreamKey(tenantId, "BillingPeriod", periodId)
        val current = replay(stream, BillingPeriodState.Empty, BillingPeriodReducer, replayRuntime)
        val event = BillingCloseStarted(cutoff)

        BillingPeriodReducer.evolve(current.state, event)
        eventStore.append(
            stream = stream,
            expectedVersion = current.streamVersion,
            events = listOf(codec.encode(event, cutoff))
        )
    }

    /**
     * `issueInvoice`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = issueInvoice(tenantId = "tenant-a", invoiceId = "demo", total = total,
     * currency =
     * "demo", now = now)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param invoiceId 요청 경계에서 검증된 `invoiceId`입니다.
     * @param total 요청 경계에서 검증된 `total`입니다.
     * @param currency 요청 경계에서 검증된 `currency`입니다.
     * @param now 요청 경계에서 검증된 `now`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun issueInvoice(tenantId: String, invoiceId: String, total: BigDecimal, currency: String, now: Instant): Boolean {
        val stream = StreamKey(tenantId, "Invoice", invoiceId)
        val current = replay(stream, InvoiceState.Empty, InvoiceReducer, replayRuntime)
        if (current.state is InvoiceState.Issued) {
            check(current.state.total == total && current.state.currency == currency) { "invoice_conflict" }
            return false
        }
        eventStore.append(
            stream = stream,
            expectedVersion = 0,
            events = listOf(codec.encode(InvoiceIssued(invoiceId, total, currency), now))
        )
        return true
    }
}

/**
 * replay은 사용량 계량·청구의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param initialState 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param reducer 업무 처리에 필요한 reducer 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param runtime 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun <S> replay(
    stream: StreamKey,
    initialState: S,
    reducer: AggregateReducer<S>,
    runtime: ReplayRuntime,
): ReplayedAggregate<S> =
    AggregateReplayer.replay(
        AggregateReplayRequest(
            runtime.eventStore.load(stream),
            initialState,
            reducer,
            null,
            ReplayPolicy(runtime.codec.registry, 1),
        ),
        runtime.metrics,
    )
/**
 * ReplayRuntime은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property eventStore 사용량 계량·청구 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property codec 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property metrics 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class ReplayRuntime(
    /**
     * `eventStore`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val eventStore: EventStore,
    /**
     * `codec`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val codec: DomainEventJsonCodec,
    /**
     * `metrics`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val metrics: ReplayTelemetry?,
)
