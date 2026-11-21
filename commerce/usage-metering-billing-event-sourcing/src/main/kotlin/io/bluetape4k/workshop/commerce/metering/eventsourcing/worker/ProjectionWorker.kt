package io.bluetape4k.workshop.commerce.metering.eventsourcing.worker

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.DomainEventJsonCodec
import io.bluetape4k.workshop.commerce.metering.eventsourcing.config.EventSourcingMetrics
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionFailureRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.NewProjectionFailure
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionCoordinator
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionEventContext
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionHandlers
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionLease
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.*
/**
 * ProjectionBatchResult은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property acquired 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property applied 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property checkpoint 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property lag 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


data class ProjectionBatchResult(val acquired: Boolean, val applied: Int, val checkpoint: Long, val lag: Long = 0)
/**
 * ProjectionWorker은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property processor 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class ProjectionWorker(
    transactionManager: PlatformTransactionManager,
    /**
     * `processor`은 사용량 과금·정산에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val processor: ProjectionBatchProcessor,
    /**
     * `metrics`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: EventSourcingMetrics,
) {

    private companion object: KLogging() {
        /**
         * `DEFAULT_BATCH_SIZE`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val DEFAULT_BATCH_SIZE = 100
        /**
         * `MAX_BATCH_SIZE`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val MAX_BATCH_SIZE = 1_000
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val PROJECTION_SUCCESS = "success"
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val PROJECTION_FAILURE = "failure"
    }
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val transactions = TransactionTemplate(transactionManager)

    /**
     * runOnce은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param ownerToken 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun runOnce(
        projectionName: String,
        generation: Int,
        ownerToken: UUID,
        limit: Int = DEFAULT_BATCH_SIZE,
    ): ProjectionBatchResult {
        limit.requireInRange(1, MAX_BATCH_SIZE, "limit")

        /**
         * `lease`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val lease = transactions.execute {
            processor.acquire(projectionName, generation, ownerToken)
        } ?: return ProjectionBatchResult(false, 0, 0)

        return runCatching {
            transactions.execute { processor.applyBatch(lease, limit) }
        }.onSuccess { result ->
            metrics.recordProjection(PROJECTION_SUCCESS, result.lag)
        }.getOrElse { failure ->
            /**
             * `eventType`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
             *
             * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
             */
            val eventType = transactions.execute { processor.quarantine(lease, failure) }
            metrics.recordProjection(PROJECTION_FAILURE, 0)
            metrics.recordQuarantine(eventType)
            throw failure
        }
    }


}
/**
 * ProjectionBatchProcessor은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property eventStore 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property checkpoints 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property failures 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property applier 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class ProjectionBatchProcessor(
    /**
     * `eventStore`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventStore: EventStoreRepository,
    /**
     * `checkpoints`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val checkpoints: ProjectionCheckpointRepository,
    /**
     * `failures`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val failures: ProjectionFailureRepository,
    /**
     * `applier`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val applier: ProjectionEventApplier,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {

    private companion object: KLogging() {
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val FAILURE_LOOKAHEAD = 1
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        val LEASE_DURATION: Duration = 30.seconds()
    }

    /**
     * acquire은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param ownerToken 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    fun acquire(
        projectionName: String,
        generation: Int,
        ownerToken: UUID,
    ): ProjectionLease? =
        checkpoints.acquireLease(
            projectionName = projectionName,
            generation = generation,
            ownerToken = ownerToken,
            now = clock.instant(),
            duration = LEASE_DURATION,
        )

    /**
     * applyBatch은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun applyBatch(lease: ProjectionLease, limit: Int): ProjectionBatchResult {
        val generation = checkpoints.requireOwnership(lease)
        val observedHead = eventStore.latestGlobalPosition()
        val events = eventStore.loadAfterGlobalPosition(generation.checkpoint, limit)

        events.forEach { persisted ->
            applier.apply(lease, persisted)
        }
        val checkpoint = events.lastOrNull()?.globalPosition ?: generation.checkpoint
        val highWatermark = maxOf(generation.highWatermark, observedHead, checkpoint)

        checkpoints.raiseHighWatermark(lease, highWatermark, clock.instant())
        checkpoints.releaseLease(lease, clock.instant())

        return ProjectionBatchResult(
            acquired = true,
            applied = events.size,
            checkpoint = checkpoint,
            lag = (highWatermark - checkpoint).coerceAtLeast(0),
        )
    }

    /**
     * quarantine은 사용량 계량·청구의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun quarantine(lease: ProjectionLease, failure: Throwable): String {
        val generation = checkpoints.requireOwnership(lease)
        val persisted = eventStore.loadAfterGlobalPosition(generation.checkpoint, FAILURE_LOOKAHEAD)
            .firstOrNull() ?: throw failure
        val digest = failureDigest(failure)

        failures.record(
            NewProjectionFailure(
                projectionName = lease.projectionName,
                generation = lease.generation,
                eventId = persisted.eventId,
                eventType = persisted.eventType,
                globalPosition = persisted.globalPosition,
                errorDigest = digest,
                failedAt = clock.instant(),
            ),
        )
        checkpoints.markFailed(
            lease = lease,
            failedPosition = persisted.globalPosition,
            failureDigest = digest,
            now = clock.instant()
        )

        return persisted.eventType
    }

    /**
     * failureDigest은 사용량 계량·청구 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun failureDigest(failure: Throwable): String {
        val material = "${failure::class.qualifiedName}:${failure.message.orEmpty()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toUtf8Bytes())
            .joinToString("") { byte ->
                "%02x".format(Locale.ROOT, byte)
            }
    }
}
/**
 * ProjectionEventApplier은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property coordinator 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property handlers 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property codec 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


@Component
class ProjectionEventApplier(
    /**
     * `coordinator`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val coordinator: ProjectionCoordinator,
    /**
     * `handlers`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val handlers: ProjectionHandlers,
    /**
     * `codec`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val codec: DomainEventJsonCodec,
) {

    companion object: KLogging()

    /**
     * apply은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param persisted 업무 처리에 필요한 persisted 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun apply(lease: ProjectionLease, persisted: PersistedEvent) {
        val decoded = codec.registry.decode(
            eventType = persisted.eventType,
            schemaVersion = persisted.schemaVersion,
            payload = persisted.payload,
        ) as DomainEvent

        val context = ProjectionEventContext(
            projectionName = lease.projectionName,
            generation = lease.generation,
            tenantId = persisted.stream.tenantId,
            eventId = persisted.eventId,
            globalPosition = persisted.globalPosition,
            occurredAt = persisted.occurredAt,
        )

        coordinator.apply(lease, persisted.eventId, persisted.globalPosition) {
            handlers.handle(context, decoded)
        }
    }
}
