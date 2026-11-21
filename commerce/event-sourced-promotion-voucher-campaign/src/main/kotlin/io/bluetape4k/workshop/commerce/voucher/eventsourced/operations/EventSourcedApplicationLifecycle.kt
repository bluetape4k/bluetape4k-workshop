package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** 이 process가 traffic을 받기 전에 PostgreSQL authority를 검증합니다. */
/** 이 process가 traffic을 받기 전에 PostgreSQL authority를 검증합니다. */
internal fun interface EventSourcedStartupProbe {
    /**
     * verify은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun verify()
}

/**
 * EventSourcedRuntimeWorkers은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface EventSourcedRuntimeWorkers {
    /**
     * start은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun start()

    /**
     * stopProjection은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun stopProjection()

    /**
     * stopRebuild은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun stopRebuild()

    /**
     * stopMaintenance은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun stopMaintenance()

    /**
     * releaseFencedLeases은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun releaseFencedLeases()
}

/**
 * application readiness transition을 소유하고,
 * Spring이 shared infrastructure를 destroy하기 전에 graceful coordinator를 호출합니다.
 */
@Component
internal class EventSourcedApplicationLifecycle(
    /**
     * `applicationContext`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val applicationContext: ApplicationContext,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val state: EventSourcedOperationalState,
    gate: EventSourcedDatabasePermitGate,
    /**
     * `probe`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val probe: EventSourcedStartupProbe,
    /**
     * `workers`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val workers: EventSourcedRuntimeWorkers,
): SmartLifecycle {
    /**
     * `running`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val running = AtomicBoolean()
    /**
     * `coordinator`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val coordinator = EventSourcedLifecycleCoordinator(
        state,
        gate,
        EventSourcedWorkerShutdown(
            projection = workers::stopProjection,
            rebuild = workers::stopRebuild,
            maintenance = workers::stopMaintenance,
            releaseFencedLeases = workers::releaseFencedLeases,
        ),
    )

    /**
     * start은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun start() {
        if (!running.compareAndSet(false, true)) return

        runCatching(probe::verify)
            .onSuccess {
                workers.start()
                state.markReady()
                AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC)
            }.onFailure { failure ->
                state.markAuthorityFailure()
                AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)
                log.warn { "event_sourced_startup_authority_probe_failed cause=${failure.javaClass.simpleName}" }
            }
    }

    /**
     * stop은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param callback 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun stop(callback: Runnable) {
        if (running.compareAndSet(true, false)) {
            state.beginShutdown()
            AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)
            coordinator.shutdown()
        }
        callback.run()
    }

    /**
     * stop은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun stop() = stop {}

    /**
     * isRunning은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun isRunning(): Boolean = running.get()

    /**
     * isAutoStartup은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun isAutoStartup(): Boolean = true

    /**
     * getPhase은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun getPhase(): Int = Int.MAX_VALUE - LIFECYCLE_PHASE_OFFSET

    private companion object: KLogging() {
        /**
         * `LIFECYCLE_PHASE_OFFSET`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        private const val LIFECYCLE_PHASE_OFFSET = 100
    }
}
