package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.javatimes.millis
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.StreamReference
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * `MIN_POLL_BACKOFF_MILLIS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MIN_POLL_BACKOFF_MILLIS = 100L
/**
 * `MAX_POLL_BACKOFF_MILLIS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MAX_POLL_BACKOFF_MILLIS = 2_000L
/**
 * `MIN_POLL_BACKOFF`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private val MIN_POLL_BACKOFF: Duration = MIN_POLL_BACKOFF_MILLIS.millis()
/**
 * `MAX_POLL_BACKOFF`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private val MAX_POLL_BACKOFF: Duration = MAX_POLL_BACKOFF_MILLIS.millis()

/**
 * CommittedProjectionBatch은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class CommittedProjectionBatch(
    /**
     * `events`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val events: List<EventEnvelope>,
    /**
     * `committedHead`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val committedHead: Long,
) {
    init {
        events.size.requireLe(MAX_PROJECTION_BATCH_EVENTS, "events.size")
        committedHead.requireZeroOrPositiveNumber("committedHead")
    }
}

/**
 * ProjectionEventReader은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface ProjectionEventReader {
    /**
     * loadAfter은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param globalPosition 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun loadAfter(globalPosition: Long): CommittedProjectionBatch
}

/** caller가 projection transaction을 소유하는 동안 committed keyset page 하나를 읽습니다. */
/** caller가 projection transaction을 소유하는 동안 committed keyset page 하나를 읽습니다. */
internal class ExposedProjectionEventReader: ProjectionEventReader {

    /**
     * loadAfter은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param globalPosition 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun loadAfter(globalPosition: Long): CommittedProjectionBatch {
        TransactionManager.current()
        globalPosition.requireZeroOrPositiveNumber("globalPosition")

        val events = EventLog
            .selectAll()
            .where { EventLog.globalPosition greater globalPosition }
            .orderBy(EventLog.globalPosition to SortOrder.ASC)
            .limit(MAX_PROJECTION_BATCH_EVENTS)
            .map(::toProjectionEnvelope)
            .takeWithinByteCap()
        val committedHead = EventLog
            .selectAll()
            .orderBy(EventLog.globalPosition to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(EventLog.globalPosition)
            ?: 0L

        return CommittedProjectionBatch(events, committedHead)
    }
}

/**
 * `ProjectionEventHandler`은 이벤트 기반 프로모션 바우처 캠페인 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface ProjectionEventHandler {
    /**
     * verify은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param event 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun verify(event: EventEnvelope)
}

/**
 * AcceptingProjectionEventHandler은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal object AcceptingProjectionEventHandler: ProjectionEventHandler {
    /**
     * verify은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param event 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun verify(event: EventEnvelope) = Unit
}

/**
 * ProjectionPoisonException은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class ProjectionPoisonException(
    /**
     * `reasonClass`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reasonClass: String,
): IllegalArgumentException("projection event cannot be handled: $reasonClass") {
    init {
        reasonClass.requireNotBlank("reasonClass")
    }
}

/**
 * ProjectionPollResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal sealed interface ProjectionPollResult {
    /**
     * Applied은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Applied(
        /**
         * `appliedEventCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val appliedEventCount: Int,
        /**
         * `duplicateEventCount`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val duplicateEventCount: Int,
        /**
         * `processedBytes`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val processedBytes: Int,
        /**
         * `checkpointPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val checkpointPosition: Long,
        /**
         * `lag`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val lag: Long,
        /**
         * `lagAge`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val lagAge: Duration,
    ): ProjectionPollResult

    /**
     * Idle은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data object Idle: ProjectionPollResult

    /**
     * Degraded은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Degraded(
        /**
         * `eventId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val eventId: UUID,
        /**
         * `reasonClass`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val reasonClass: String,
        /**
         * `attempts`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val attempts: Int,
        /**
         * `retryable`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val retryable: Boolean,
        /**
         * `operatorAction`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val operatorAction: ProjectionOperatorAction,
    ): ProjectionPollResult
}

/**
 * ProjectionOperatorAction은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class ProjectionOperatorAction {
    /**
     * `ProjectionOperatorAction.RETRY_POISON_EVENT`은 ProjectionOperatorAction 업무 상태의
     * `RETRY_POISON_EVENT`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    RETRY_POISON_EVENT,
}

/**
 * ProjectionTransactionRunner은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface ProjectionTransactionRunner {
/**
 * `inTransaction`은 이벤트 기반 프로모션 바우처 캠페인 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    fun <T> inTransaction(block: () -> T): T
}

/**
 * ExposedProjectionTransactionRunner은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class ExposedProjectionTransactionRunner(
    database: Database,
    permits: EventSourcedDatabasePermitGate,
): ProjectionTransactionRunner {
    /**
     * `transactions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions =
        EventSourcedPermitTransactionRunner(database, permits, EventSourcedDatabaseLane.PROJECTION)

/**
 * `inTransaction`은 이벤트 기반 프로모션 바우처 캠페인 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    override fun <T> inTransaction(block: () -> T): T =
        transactions.inTransaction(block)
}

/** bounded poll 하나입니다. scheduling과 cooperative cancellation은 caller가 소유합니다. */
/** bounded poll 하나입니다. scheduling과 cooperative cancellation은 caller가 소유합니다. */
internal class ProjectionWorker(
    /**
     * `repository`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: ProjectionRepository,
    /**
     * `reader`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val reader: ProjectionEventReader,
    /**
     * `handler`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val handler: ProjectionEventHandler = AcceptingProjectionEventHandler,
) {

    /**
     * poll은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun poll(
        key: ProjectionKey,
        lease: ProjectionLease,
        now: Instant,
    ): ProjectionPollResult {
        val checkpoint = repository.checkpoint(key)?.position ?: 0L
        val batch = reader.loadAfter(checkpoint)
        if (batch.events.isEmpty()) {
            return ProjectionPollResult.Idle
        }
        return handleBatch(key, lease, batch, now)
    }

    /**
     * handleBatch은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun handleBatch(
        key: ProjectionKey,
        lease: ProjectionLease,
        batch: CommittedProjectionBatch,
        now: Instant,
    ): ProjectionPollResult {
        batch.events.forEach { event ->
            try {
                handler.verify(event)
            } catch (exception: ProjectionPoisonException) {
                val poison = repository.poison(key, lease, event, exception.reasonClass, now)
                return poison.toDegradedResult(exception.reasonClass)
            }
        }
        return repository.applyBatch(key, lease, batch.events, now).toPollResult(batch, now)
    }
}

/**
 * ProjectionDelay은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface ProjectionDelay {
    /**
     * schedule은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param delay 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param action 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun schedule(delay: Duration, action: () -> Unit): ProjectionScheduledTask
}

/**
 * ProjectionScheduledTask은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface ProjectionScheduledTask {
    /**
     * cancel은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun cancel()
}

/**
 * ProjectionPollingLoop은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class ProjectionPollingLoop(
    /**
     * `transactions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions: ProjectionTransactionRunner,
    /**
     * `leases`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val leases: ProjectionLeaseRepository,
    /**
     * `worker`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val worker: ProjectionWorker,
    /**
     * `delay`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val delay: ProjectionDelay,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: () -> Instant,
) {
    /**
     * start은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun start(
        key: ProjectionKey,
        ownerDigest: String,
    ): ProjectionLoopHandle {
        val handle = ProjectionLoopHandle()
        scheduleNext(handle, key, ownerDigest, null, MIN_POLL_BACKOFF)
        return handle
    }

    /**
     * scheduleNext은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun scheduleNext(
        handle: ProjectionLoopHandle,
        key: ProjectionKey,
        ownerDigest: String,
        lease: ProjectionLease?,
        backoff: Duration,
    ) {
        if (handle.cancelled) return
        handle.task = delay.schedule(backoff) {
            val outcome = poll(key, ownerDigest, lease)
            if (outcome.continuePolling) {
                scheduleNext(handle, key, ownerDigest, outcome.lease, outcome.nextBackoff)
            }
        }
    }

    /**
     * poll은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun poll(
        key: ProjectionKey,
        ownerDigest: String,
        existingLease: ProjectionLease?,
    ): PollOutcome =
        transactions.inTransaction {
            val now = clock()
            val activeLease = refreshLease(key, ownerDigest, existingLease, now)
            if (activeLease == null) {
                PollOutcome(null, nextBackoff = MAX_POLL_BACKOFF)
            } else {
                worker.poll(key, activeLease, now).toOutcome(activeLease)
            }
        }

    /**
     * refreshLease은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun refreshLease(
        key: ProjectionKey,
        ownerDigest: String,
        existingLease: ProjectionLease?,
        now: Instant,
    ): ProjectionLease? =
        existingLease?.let { lease ->
            if (lease.isRenewalDue(now)) {
                leases.renewLease(key.projection, key.generation, lease, now)
            } else {
                lease
            }
        } ?: leases.acquire(key.projection, key.generation, ownerDigest, now)
}

/**
 * ProjectionLoopHandle은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class ProjectionLoopHandle {
    @Volatile
    /**
     * `cancelled`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    internal var cancelled: Boolean = false

    @Volatile
    /**
     * `task`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    internal var task: ProjectionScheduledTask? = null

    /**
     * cancel은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun cancel() {
        cancelled = true
        task?.cancel()
    }
}

/**
 * PollOutcome은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
private data class PollOutcome(
    /**
     * `lease`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val lease: ProjectionLease?,
    /**
     * `nextBackoff`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val nextBackoff: Duration,
    /**
     * `continuePolling`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val continuePolling: Boolean = true,
)

/**
 * `toPollResult`은 이벤트 기반 프로모션 바우처 캠페인 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun ProjectionApplyResult.toPollResult(
    batch: CommittedProjectionBatch,
    now: Instant,
): ProjectionPollResult.Applied {
    val lag = (batch.committedHead - checkpoint.position).coerceAtLeast(0)

    val lagAge = if (lag == 0L) {
        Duration.ZERO
    } else {
        Duration.between(batch.events.last().recordedAt, now).coerceAtLeast(Duration.ZERO)
    }

    return ProjectionPollResult.Applied(
        appliedEventCount = appliedEventCount,
        duplicateEventCount = duplicateEventCount,
        processedBytes = batch.events.sumOf { event ->
            event.payload.canonicalJson.toUtf8Bytes().size
        },
        checkpointPosition = checkpoint.position,
        lag = lag,
        lagAge = lagAge,
    )
}

/**
 * `toDegradedResult`은 이벤트 기반 프로모션 바우처 캠페인 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun ProjectionPoisonRecord.toDegradedResult(reasonClass: String): ProjectionPollResult.Degraded =
    ProjectionPollResult.Degraded(
        eventId = eventId,
        reasonClass = reasonClass,
        attempts = attempts,
        retryable = attempts < MAX_PROJECTION_POISON_ATTEMPTS,
        operatorAction = ProjectionOperatorAction.RETRY_POISON_EVENT,
    )

/**
 * `toOutcome`은 이벤트 기반 프로모션 바우처 캠페인 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun ProjectionPollResult.toOutcome(lease: ProjectionLease): PollOutcome =
    when (this) {
        is ProjectionPollResult.Applied -> PollOutcome(lease, MIN_POLL_BACKOFF)
        ProjectionPollResult.Idle       -> PollOutcome(lease, MAX_POLL_BACKOFF)
        is ProjectionPollResult.Degraded -> PollOutcome(lease, MAX_POLL_BACKOFF, retryable)
    }

private fun List<EventEnvelope>.takeWithinByteCap(): List<EventEnvelope> {
    var accumulated = 0
    return takeWhile { event ->
        accumulated += event.payload.canonicalJson.toUtf8Bytes().size
        accumulated <= MAX_PROJECTION_BATCH_BYTES
    }
}

/**
 * toProjectionEnvelope은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param row 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
internal fun toProjectionEnvelope(row: org.jetbrains.exposed.v1.core.ResultRow): EventEnvelope =
    EventEnvelope(
        eventId = row[EventLog.id].value,
        tenantId = TenantId(row[EventLog.tenantId]),
        stream = StreamReference(row[EventLog.streamType], row[EventLog.streamId], row[EventLog.streamVersion]),
        globalPosition = row[EventLog.globalPosition],
        eventType = row[EventLog.eventType],
        schemaVersion = row[EventLog.schemaVersion],
        occurredAt = row[EventLog.occurredAt],
        recordedAt = row[EventLog.recordedAt],
        correlationId = row[EventLog.correlationId],
        causationId = row[EventLog.causationId],
        actorSurrogate = row[EventLog.actorSurrogate],
        payload = EventPayload(row[EventLog.payload]),
        actorHmacKeyVersion = row[EventLog.actorHmacKeyVersion],
    )
