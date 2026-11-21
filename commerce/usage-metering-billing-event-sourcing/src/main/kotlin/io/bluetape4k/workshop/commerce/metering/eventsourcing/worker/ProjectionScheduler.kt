package io.bluetape4k.workshop.commerce.metering.eventsourcing.worker

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationQueryRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionRebuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
/**
 * ProjectionCycle은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property activeGeneration 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property buildingGeneration 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property buildingResult 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


data class ProjectionCycle(
    /**
     * `activeGeneration`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val activeGeneration: Int,
    /**
     * `buildingGeneration`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val buildingGeneration: Int?,
    /**
     * `buildingResult`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val buildingResult: ProjectionBatchResult?,
)
/**
 * ProjectionGenerationRunner은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property worker 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property generations 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property generationQueries 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 */


@Component
class ProjectionGenerationRunner(
    transactionManager: PlatformTransactionManager,
    /**
     * `worker`은 사용량 과금·정산에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val worker: ProjectionWorker,
    /**
     * `generations`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val generations: ProjectionGenerationRepository,
    /**
     * `generationQueries`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val generationQueries: ProjectionGenerationQueryRepository,
) {
    companion object: KLogging()
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
     */
    fun runOnce(projectionName: String): ProjectionCycle? {
        val active = transactions.execute { generations.active(projectionName) } ?: return null
        worker.runOnce(projectionName, active.generation, Uuid.V7.nextId())

        val building = transactions.execute {
            generationQueries.building(projectionName)
        }
        val buildingResult = building?.let {
            worker.runOnce(projectionName, it.generation, Uuid.V7.nextId())
        }
        return ProjectionCycle(
            activeGeneration = active.generation,
            buildingGeneration = building?.generation,
            buildingResult = buildingResult
        )
    }
}
/**
 * ProjectionGenerationSwitcher은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property checkpoints 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property rebuilder 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property eventStore 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class ProjectionGenerationSwitcher(
    transactionManager: PlatformTransactionManager,
    /**
     * `checkpoints`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val checkpoints: ProjectionCheckpointRepository,
    /**
     * `rebuilder`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val rebuilder: ProjectionRebuilder,
    /**
     * `eventStore`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventStore: EventStore,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {

    private companion object: KLogging() {
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        val SWITCH_LEASE: Duration = 30.seconds()
    }
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val transactions = TransactionTemplate(transactionManager)

    /**
     * switchIfCaughtUp은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param cycle 업무 처리에 필요한 cycle 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun switchIfCaughtUp(projectionName: String, cycle: ProjectionCycle): Boolean {
        val generation = cycle.buildingGeneration
        val result = cycle.buildingResult
        val switchReady = result?.let { it.acquired && it.lag == 0L } == true
        if (generation == null || !switchReady) return false

        return transactions.execute {
            val now = clock.instant()
            val lease = checkpoints.acquireLease(
                projectionName = projectionName,
                generation = generation,
                ownerToken = Uuid.V7.nextId(),
                now = now,
                duration = SWITCH_LEASE,
            ) ?: return@execute false

            rebuilder.catchUpAndSwitch(
                lease = lease,
                expectedActiveGeneration = cycle.activeGeneration,
                observedHighWatermark = eventStore.latestGlobalPosition(),
                now = now
            )
        }
    }
}

@Component
@ConditionalOnProperty(
    prefix = "workshop.metering-events.projection",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
/**
 * ProjectionScheduler은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property runner 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property switcher 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */

class ProjectionScheduler(
    /**
     * `runner`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val runner: ProjectionGenerationRunner,
    /**
     * `switcher`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val switcher: ProjectionGenerationSwitcher,
) {

    private companion object: KLogging() {
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val BILLING_PROJECTION = "billing"
    }

    @Scheduled(
        fixedDelayString = "\${workshop.metering-events.projection.fixed-delay:1s}",
        initialDelayString = "\${workshop.metering-events.projection.initial-delay:1s}",
    )
    /**
     * runOnce은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun runOnce() {
        val cycle = runner.runOnce(BILLING_PROJECTION) ?: return
        switcher.switchIfCaughtUp(BILLING_PROJECTION, cycle)
    }
}
