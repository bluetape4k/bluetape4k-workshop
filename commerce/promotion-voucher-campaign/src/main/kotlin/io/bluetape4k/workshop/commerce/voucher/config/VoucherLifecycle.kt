package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationScheduler
import io.bluetape4k.workshop.commerce.voucher.web.VoucherEventStream
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `VoucherShutdownEvent`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class VoucherShutdownEvent {
    /**
     * `VoucherShutdownEvent.READINESS_DOWN`은 `readiness down` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된
     * 전이를 확인한 뒤 사용합니다.
     */
    READINESS_DOWN,
    /**
     * `VoucherShutdownEvent.REJECT_NEW`은 `reject new` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한 뒤
     * 사용합니다.
     */
    REJECT_NEW,
    /**
     * `VoucherShutdownEvent.STOP_WORKER`은 `stop worker` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한
     * 뒤 사용합니다.
     */
    STOP_WORKER,
    /**
     * `VoucherShutdownEvent.AWAIT_DB`은 `await db` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    AWAIT_DB,
    /**
     * `VoucherShutdownEvent.STOP_SSE`은 `stop sse` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    STOP_SSE,
    /**
     * `VoucherShutdownEvent.RELEASE_LEADER`은 `release leader` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된
     * 전이를 확인한 뒤 사용합니다.
     */
    RELEASE_LEADER,
    /**
     * `VoucherShutdownEvent.CLOSE_REDIS`은 `close redis` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한
     * 뒤 사용합니다.
     */
    CLOSE_REDIS,
    /**
     * `VoucherShutdownEvent.CLOSE_EXECUTOR`은 `close executor` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된
     * 전이를 확인한 뒤 사용합니다.
     */
    CLOSE_EXECUTOR,
}

/**
 * `VoucherShutdownReason`은 사유 코드입니다.
 * 호출자가 재시도·거절·대사·운영자 조치를 분기하는 오류 계약입니다.
 */
internal enum class VoucherShutdownReason {
    /**
     * `VoucherShutdownReason.CLEAN`은 `clean` 업무 상황을 나타냅니다. 호출자는 이 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로
     * 사용합니다.
     */
    CLEAN,
    /**
     * `VoucherShutdownReason.DB_DRAIN_DEADLINE`은 `db drain deadline` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    DB_DRAIN_DEADLINE,
}

/**
 * 단일 주입 grace deadline을 사용하는 결정적 shutdown state machine입니다.
 * @property gate 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property readinessDown 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property stopWorker 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property stopSse 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property releaseLeader 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property closeRedis 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property closeExecutor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property graceDeadline 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 */
internal class VoucherLifecycleCoordinator(
    /**
     * `gate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val gate: DatabasePermitGate,
    /**
     * `readinessDown`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val readinessDown: () -> Unit = {},
    /**
     * `stopWorker`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val stopWorker: () -> Unit,
    /**
     * `stopSse`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val stopSse: () -> Unit,
    /**
     * `releaseLeader`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val releaseLeader: () -> Unit,
    /**
     * `closeRedis`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val closeRedis: () -> Unit,
    /**
     * `closeExecutor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val closeExecutor: () -> Unit,
    /**
     * `graceDeadline`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val graceDeadline: Duration = 30.seconds(),
) {

    companion object: KLogging()

    /**
     * `shutdown`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val shutdown = AtomicBoolean()
    /**
     * `eventLock`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventLock = ReentrantLock()
    /**
     * `eventRecorded`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventRecorded = eventLock.newCondition()
    /**
     * `recordedEvents`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val recordedEvents = ArrayList<VoucherShutdownEvent>()

    @Volatile
    /**
     * `reason`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private var reason = VoucherShutdownReason.CLEAN

    init {
        require(!graceDeadline.isNegative && !graceDeadline.isZero) { "graceDeadline must be positive" }
    }

    /**
     * shutdown은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun shutdown() {
        if (!shutdown.compareAndSet(false, true)) return

        log.info { "voucher shutdown starting ..." }

        val deadline = System.nanoTime() + graceDeadline.toNanos()
        step(VoucherShutdownEvent.READINESS_DOWN, readinessDown)
        step(VoucherShutdownEvent.REJECT_NEW, gate::beginShutdown)
        step(VoucherShutdownEvent.STOP_WORKER, stopWorker)

        record(VoucherShutdownEvent.AWAIT_DB)
        if (!gate.awaitDrained(remaining(deadline))) {
            reason = VoucherShutdownReason.DB_DRAIN_DEADLINE
            log.warn { "voucher_shutdown_forced reason=$reason" }
        }
        step(VoucherShutdownEvent.STOP_SSE, stopSse)
        step(VoucherShutdownEvent.RELEASE_LEADER, releaseLeader)
        step(VoucherShutdownEvent.CLOSE_REDIS, closeRedis)
        step(VoucherShutdownEvent.CLOSE_EXECUTOR, closeExecutor)

        log.info { "voucher_shutdown_completed reason=$reason" }
    }

    /**
     * events은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun events(): List<VoucherShutdownEvent> =
        eventLock.withLock { recordedEvents.toList() }

    /**
     * forcedReason은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun forcedReason(): VoucherShutdownReason = reason

    /**
     * awaitEvent은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param expected 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun awaitEvent(
        expected: VoucherShutdownEvent,
        timeout: Duration,
    ): Boolean {
        var remaining = timeout.toNanos()
        eventLock.withLock {
            while (expected !in recordedEvents && remaining > 0) {
                remaining = eventRecorded.awaitNanos(remaining)
            }
            return expected in recordedEvents
        }
    }

    /**
     * step은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private inline fun step(
        event: VoucherShutdownEvent,
        action: () -> Unit,
    ) {
        record(event)
        action()
    }

    /**
     * record은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun record(event: VoucherShutdownEvent) {
        eventLock.withLock {
            recordedEvents += event
            eventRecorded.signalAll()
        }
    }

    /**
     * remaining은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param deadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun remaining(deadline: Long): Duration =
        (deadline - System.nanoTime()).coerceAtLeast(0).nanos()

}

/** Spring이 infrastructure bean을 destroy하기 전에 application-owned shutdown sequence를 실행합니다. */
/** Spring이 infrastructure bean을 destroy하기 전에 application-owned shutdown sequence를 실행합니다. */
@Component
internal class VoucherLifecycle(
    applicationContext: ApplicationContext,
    gate: DatabasePermitGate,
    scheduler: ObjectProvider<VoucherReconciliationScheduler>,
    streams: VoucherEventStream,
    redis: ObjectProvider<VoucherRedisResources>,
    executorShutdown: VoucherExecutorShutdown,
): SmartLifecycle {

    companion object: KLogging()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val running = AtomicBoolean()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val coordinator = VoucherLifecycleCoordinator(
        gate = gate,
        readinessDown = {
            AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)
        },
        stopWorker = { scheduler.ifAvailable?.stop() },
        stopSse = streams::close,
        releaseLeader = { log.info { "voucher_leader_release_completed" } },
        closeRedis = { redis.ifAvailable?.close() },
        closeExecutor = executorShutdown::close,
    )

    /**
     * start은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun start() {
        running.set(true)
    }

    /**
     * stop은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param callback 업무 처리에 필요한 callback 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun stop(callback: Runnable) {
        if (running.compareAndSet(true, false)) {
            coordinator.shutdown()
        }
        callback.run()
    }

    /**
     * stop은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun stop() = stop {}

    /**
     * isRunning은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun isRunning(): Boolean = running.get()

    /**
     * isAutoStartup은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun isAutoStartup(): Boolean = true

    /**
     * getPhase은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun getPhase(): Int = Int.MAX_VALUE - 100

    /**
     * events은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    internal fun events(): List<VoucherShutdownEvent> = coordinator.events()
}
