package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingCloseBatchRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodFinalized
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.priceAt
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OccurredEventCursor
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.ReplayTelemetry
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.StreamAppend
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

/**
 * BillingCloseBatchResult은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
enum class BillingCloseBatchResult { APPLIED, FINALIZED, ALREADY_FINALIZED, RETRY }

/**
 * BillingTelemetry은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
interface BillingTelemetry : ReplayTelemetry {
    /**
     * recordCloseBatch은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param outcome 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param size 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun recordCloseBatch(outcome: String, size: Int)
    /**
     * recordReconciliation은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param kind 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun recordReconciliation(kind: String)
}

@Service
/**
 * BillingCloseService은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class BillingCloseService(
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
    /**
     * `metrics`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: BillingTelemetry? = null,
) {

    companion object: KLogging()

    /**
     * `replayRuntime`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val replayRuntime = ReplayRuntime(eventStore, codec, metrics)

    /**
     * closeNextBatch은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param periodId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param batchSize 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun closeNextBatch(tenantId: String, periodId: String, batchSize: Int, now: Instant): BillingCloseBatchResult {
        val periodStream = StreamKey(tenantId, "BillingPeriod", periodId)
        val period = replay(periodStream, BillingPeriodState.Empty, BillingPeriodReducer, replayRuntime)
        if (period.state is BillingPeriodState.Finalized) {
            return BillingCloseBatchResult.ALREADY_FINALIZED.also { metrics?.recordCloseBatch(it.metricValue, 0) }
        }
        val closing = period.state as? BillingPeriodState.Closing ?: error("billing_period_not_closing")
        val cursor = closing.throughOccurredAt?.let {
            OccurredEventCursor(it, java.util.UUID.fromString(checkNotNull(closing.throughEventId)))
        }
        val usages = eventStore.loadByType(
            EventTypeQuery(tenantId, "usage.accepted", closing.startsAt, closing.endsAt, cursor, batchSize),
        )

        return try {
            if (usages.isEmpty()) finalize(periodStream, period.streamVersion, closing, now)
            else rateBatch(periodStream, period.streamVersion, closing, usages, now)
        } catch (_: OptimisticConcurrencyException) {
            BillingCloseBatchResult.RETRY
        }.also {
            metrics?.recordCloseBatch(it.metricValue, usages.size)
        }
    }

    /**
     * rateBatch은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun rateBatch(
        periodStream: StreamKey,
        periodVersion: Long,
        closing: BillingPeriodState.Closing,
        usages: List<PersistedEvent>,
        now: Instant,
    ): BillingCloseBatchResult {
        val rated = usages.map { persisted ->
            val usage = codec.registry.decode(
                eventType = persisted.eventType,
                schemaVersion = persisted.schemaVersion,
                payload = persisted.payload,
            ) as UsageAccepted

            val meterStream = StreamKey(persisted.stream.tenantId, "Meter", usage.meterCode)
            val meter = replay(meterStream, MeterState.Empty, MeterReducer, replayRuntime).state
            val price = meter.priceAt(usage.occurredAt)
            val amount = usage.quantity.multiply(price.unitPrice)
            val event = UsageRated(
                usageEventId = persisted.eventId.toString(),
                meterCode = usage.meterCode,
                quantity = usage.quantity,
                unitPrice = price.unitPrice,
                amount = amount,
                currency = price.currency,
            )
            Triple(persisted, event, amount)
        }
        val last = rated.last().first
        val batchTotal = rated
            .fold(BigDecimal.ZERO) { total, item ->
                total + item.third
            }
        val progress = BillingCloseBatchRated(
            throughOccurredAt = last.occurredAt,
            throughEventId = last.eventId.toString(),
            batchAmount = batchTotal,
            currency = closing.currency
        )
        val appends = rated.map { (persisted, event) ->
            StreamAppend(
                StreamKey(periodStream.tenantId, "Rating", persisted.eventId.toString()),
                0,
                listOf(codec.encode(event, persisted.occurredAt)),
            )
        } + StreamAppend(
            stream = periodStream,
            expectedVersion = periodVersion,
            events = listOf(codec.encode(progress, now))
        )
        eventStore.appendAll(appends)
        return BillingCloseBatchResult.APPLIED
    }

    /**
     * finalize은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun finalize(
        stream: StreamKey,
        version: Long,
        closing: BillingPeriodState.Closing,
        now: Instant,
    ): BillingCloseBatchResult {
        val event = BillingPeriodFinalized(closing.total, closing.currency)
        eventStore.append(
            stream = stream,
            expectedVersion = version,
            events = listOf(codec.encode(event, now))
        )
        return BillingCloseBatchResult.FINALIZED
    }
}

/**
 * BillingCloseBatchResult은 사용량·청구 업무에서 상태·식별자·계산 결과를 전달하는 값입니다.
 *
 * 값의 범위와 변경 시점을 업무 계약에 맞춰 유지해야 하며, 경쟁 요청·재시도·복구 시에도 저장소와 API가 같은 의미로 해석해야 합니다.
 */
private val BillingCloseBatchResult.metricValue: String
    get() = name.lowercase()
