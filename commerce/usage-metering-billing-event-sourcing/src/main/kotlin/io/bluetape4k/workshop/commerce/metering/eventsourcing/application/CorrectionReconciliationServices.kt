package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentDirection
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentPosted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OccurredEventCursor
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.ReplayTelemetry
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.BillingReadModelRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.*

@Service
/**
 * AdjustmentCommandService은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class AdjustmentCommandService(
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
     * `replayRuntime`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val replayRuntime = ReplayRuntime(eventStore, codec, metrics)

    /**
     * post은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun post(
        tenantId: String,
        adjustmentId: String,
        adjustment: AdjustmentPosted,
        now: Instant,
    ): Boolean {
        val stream = StreamKey(tenantId, "Adjustment", adjustmentId)
        val current = replay(stream, AdjustmentState.Empty, AdjustmentReducer, replayRuntime)

        if (current.state is AdjustmentState.Posted) {
            check(current.state.adjustment == adjustment) { "adjustment_conflict" }
            return false
        }
        eventStore.append(
            stream = stream,
            expectedVersion = 0,
            events = listOf(codec.encode(adjustment, now))
        )
        return true
    }
}

/**
 * ReconciliationQuery은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
data class ReconciliationQuery(
    /**
     * `tenantId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `projectionName`은 사용량 과금·정산에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val projectionName: String,
    /**
     * `startsAt`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val startsAt: Instant,
    /**
     * `endsAt`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val endsAt: Instant,
)

/**
 * ReconciliationFindingType은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
enum class ReconciliationFindingType { AMOUNT_MISMATCH }

/**
 * ReconciliationFinding은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
data class ReconciliationFinding(
    /**
     * `type`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val type: ReconciliationFindingType,
    /**
     * `expectedAmount`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val expectedAmount: BigDecimal,
    /**
     * `actualAmount`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val actualAmount: BigDecimal,
    /**
     * `generation`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Int,
    /**
     * `observedPosition`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val observedPosition: Long,
    /**
     * `digest`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val digest: String,
)

@Service
/**
 * ReconciliationService은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class ReconciliationService(
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
     * `generations`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val generations: ProjectionGenerationRepository,
    /**
     * `readModels`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val readModels: BillingReadModelRepository,
    /**
     * `metrics`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: BillingTelemetry? = null,
) {

    private companion object: KLogging() {
        /**
         * `EVENT_PAGE_SIZE`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val EVENT_PAGE_SIZE = 1_000
    }

    /**
     * inspect은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param query 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun inspect(query: ReconciliationQuery): ReconciliationFinding? {
        val active = checkNotNull(generations.active(query.projectionName)) { "active_projection_missing" }
        val expected = financialEvents(query)
            .fold(BigDecimal.ZERO) { total, persisted ->
                when (val event =
                    codec.registry.decode(persisted.eventType, persisted.schemaVersion, persisted.payload)) {
                    is UsageRated       -> total + event.amount
                    is AdjustmentPosted -> if (event.direction == AdjustmentDirection.CREDIT) {
                        total - event.amount
                    } else {
                        total + event.amount
                    }
                    else                -> total
                }
            }
        val actual = readModels.financialTotal(query.projectionName, active.generation, query.tenantId)
        if (expected.compareTo(actual) == 0) return null

        val material = listOf(
            query.tenantId,
            query.projectionName,
            active.generation,
            active.checkpoint,
            expected.toPlainString(),
            actual.toPlainString(),
        ).joinToString("|")

        return ReconciliationFinding(
            type = ReconciliationFindingType.AMOUNT_MISMATCH,
            expectedAmount = expected,
            actualAmount = actual,
            generation = active.generation,
            observedPosition = active.checkpoint,
            digest = sha256(material),
        ).also {
            metrics?.recordReconciliation(it.type.name.lowercase())
        }
    }

    /**
     * isStillCurrent은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param query 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param finding 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun isStillCurrent(query: ReconciliationQuery, finding: ReconciliationFinding): Boolean =
        inspect(query)
            ?.let { current ->
                current.digest == finding.digest &&
                        current.generation == finding.generation &&
                        current.observedPosition == finding.observedPosition
            } == true

    /**
     * financialEvents은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param query 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun financialEvents(query: ReconciliationQuery): List<PersistedEvent> =
        buildList {
            addAll(loadAll(query, "usage.rated"))
            addAll(loadAll(query, "adjustment.posted"))
        }

    /**
     * loadAll은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param query 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param eventType 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun loadAll(query: ReconciliationQuery, eventType: String): List<PersistedEvent> {
        val events = mutableListOf<PersistedEvent>()
        var cursor: OccurredEventCursor? = null
        do {
            val page = eventStore.loadByType(
                EventTypeQuery(
                    tenantId = query.tenantId,
                    eventType = eventType,
                    startsAt = query.startsAt,
                    endsAt = query.endsAt,
                    after = cursor,
                    limit = EVENT_PAGE_SIZE
                ),
            )
            events += page
            cursor = page.lastOrNull()?.let { OccurredEventCursor(it.occurredAt, it.eventId) }
        } while (page.size == EVENT_PAGE_SIZE)

        return events
    }

    /**
     * sha256은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param material 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun sha256(material: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(material.toUtf8Bytes())
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

}
