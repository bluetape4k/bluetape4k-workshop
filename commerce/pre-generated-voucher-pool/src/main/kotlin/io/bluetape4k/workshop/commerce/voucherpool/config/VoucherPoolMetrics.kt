@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.hashOf
import io.bluetape4k.workshop.commerce.voucherpool.admission.PermitLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcExecutionLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcTimeoutPhase
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcMetrics
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * `VoucherPoolAlert`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class VoucherPoolAlert {
    /**
     * `VoucherPoolAlert.HIKARI_PENDING`은 `hikari pending` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    HIKARI_PENDING,
    /**
     * `VoucherPoolAlert.WORKER_NO_PROGRESS`은 `worker no progress` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    WORKER_NO_PROGRESS,
    /**
     * `VoucherPoolAlert.REDIS_DEGRADED`은 보조 의존성이 제한되지만 권위 데이터만으로 일부 기능을 계속할 수 있는 값입니다.
     */
    REDIS_DEGRADED,
    /**
     * `VoucherPoolAlert.ELIGIBLE_DEPTH_LOW`은 `eligible depth low` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    ELIGIBLE_DEPTH_LOW,
    /**
     * `VoucherPoolAlert.SSE_RESET_BURST`은 `sse reset burst` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    SSE_RESET_BURST,
    /**
     * `VoucherPoolAlert.QUARANTINE_PRESENT`은 `quarantine present` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    QUARANTINE_PRESENT,
    /**
     * `VoucherPoolAlert.PURGE_LAG`은 `purge lag` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한
     * 뒤 사용합니다.
     */
    PURGE_LAG,
    /**
     * `VoucherPoolAlert.RESTORE_FAILURE`은 업무 처리가 실패해 결과를 성공으로 간주할 수 없는 값입니다.
     */
    RESTORE_FAILURE,
}
/**
 * VoucherPoolAlertWindow은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property threshold 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property duration 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */


internal class VoucherPoolAlertWindow(
    /**
     * `threshold`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val threshold: Double,
    /**
     * `duration`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val duration: Duration,
)

/**
 * VoucherPoolAlertPolicy은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object VoucherPoolAlertPolicy {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val windows: Map<VoucherPoolAlert, VoucherPoolAlertWindow> = mapOf(
        VoucherPoolAlert.HIKARI_PENDING to VoucherPoolAlertWindow(0.0, 10.seconds()),
        VoucherPoolAlert.WORKER_NO_PROGRESS to VoucherPoolAlertWindow(0.0, 30.seconds()),
        VoucherPoolAlert.REDIS_DEGRADED to VoucherPoolAlertWindow(0.0, 30.seconds()),
        VoucherPoolAlert.ELIGIBLE_DEPTH_LOW to VoucherPoolAlertWindow(0.10, 60.seconds()),
        VoucherPoolAlert.SSE_RESET_BURST to VoucherPoolAlertWindow(10.0, 1.minutes()),
        VoucherPoolAlert.QUARANTINE_PRESENT to VoucherPoolAlertWindow(1.0, Duration.ZERO),
        VoucherPoolAlert.PURGE_LAG to VoucherPoolAlertWindow(0.0, 24.hours()),
        VoucherPoolAlert.RESTORE_FAILURE to VoucherPoolAlertWindow(1.0, Duration.ZERO),
    )

    /**
     * get은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param alert 업무 처리에 필요한 alert 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    operator fun get(alert: VoucherPoolAlert): VoucherPoolAlertWindow =
        windows.getValue(alert)
}

/**
 * voucher pool을 위한 bounded-tag operational metric만 등록합니다.
 * @property registry 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
@Suppress("TooManyFunctions")
internal class VoucherPoolMetrics(
    /**
     * `registry`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val registry: MeterRegistry,
): VoucherPoolJdbcMetrics {

    private companion object: KLogging() {
        /**
         * `RATIO_SCALE`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val RATIO_SCALE = 100_000.0
        /**
         * `BOUNDED_VALUE`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val BOUNDED_VALUE = Regex("[A-Z][A-Z0-9_]{0,31}")

        /**
         * bounded은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun bounded(value: String): String =
            value.uppercase().takeIf(BOUNDED_VALUE::matches) ?: "UNKNOWN"
    }
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val hikariActive = AtomicInteger()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val hikariPending = AtomicInteger()
    /**
     * `workerBacklog`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val workerBacklog = ConcurrentHashMap<WorkerKind, AtomicLong>()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val workerOldestAge = ConcurrentHashMap<WorkerKind, AtomicLong>()
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    private val workerCheckpoint = ConcurrentHashMap<WorkerKind, AtomicLong>()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val activeSse = AtomicInteger()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val sseResets = AtomicLong()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val poolEligibleRatio = AtomicLong(RATIO_SCALE.toLong())
    /**
     * `quarantineCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val quarantineCount = AtomicLong()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val purgeLagSeconds = AtomicLong()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val restoreFailures = AtomicLong()
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val permitWaits = ConcurrentHashMap<MetricKey, Timer>()
    /**
     * 프로모션 바우처 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */
    private val degradedComponents = ConcurrentHashMap<String, Counter>()
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val jdbcTimeouts = ConcurrentHashMap<MetricKey, Counter>()

    init {
        gauge("voucher.pool.hikari.active", hikariActive)
        gauge("voucher.pool.hikari.pending", hikariPending)
        gauge("voucher.pool.sse.subscribers", activeSse)
        gauge("voucher.pool.sse.resets", sseResets)
        Gauge.builder("voucher.pool.depth.eligible.ratio", poolEligibleRatio) {
            it.get().toDouble() / RATIO_SCALE
        }.register(registry)

        gauge("voucher.pool.quarantine.count", quarantineCount)
        gauge("voucher.pool.purge.lag", purgeLagSeconds, "seconds")
        gauge("voucher.pool.restore.failures", restoreFailures)

        WorkerKind.entries.forEach { kind ->
            gauge(
                "voucher.pool.worker.backlog",
                workerBacklog.computeIfAbsent(kind) { AtomicLong() },
                tags = arrayOf("worker", kind.name),
            )
            gauge(
                "voucher.pool.worker.oldest.age",
                workerOldestAge.computeIfAbsent(kind) { AtomicLong() },
                "seconds",
                "worker",
                kind.name,
            )
            gauge(
                "voucher.pool.worker.checkpoint",
                workerCheckpoint.computeIfAbsent(kind) { AtomicLong() },
                tags = arrayOf("worker", kind.name),
            )
        }
    }

    /**
     * hikari은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param active 업무 처리에 필요한 active 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param pending 업무 처리에 필요한 pending 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun hikari(active: Int, pending: Int) {
        hikariActive.set(active.coerceAtLeast(0))
        hikariPending.set(pending.coerceAtLeast(0))
    }

    /**
     * permitWait은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun permitWait(lane: PermitLane, outcome: String, duration: Duration) {
        val key = MetricKey(lane.name, bounded(outcome))

        permitWaits.computeIfAbsent(key) {
            Timer.builder("voucher.pool.permit.wait")
                .tag("lane", it.first)
                .tag("outcome", it.second)
                .register(registry)
        }.record(duration)
    }

    /**
     * timedOut은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param phase 업무 처리에 필요한 phase 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun timedOut(lane: JdbcExecutionLane, phase: JdbcTimeoutPhase) {
        val key = MetricKey(lane.name, phase.name)
        jdbcTimeouts.computeIfAbsent(key) {
            Counter.builder("voucher.pool.jdbc.timeout")
                .tag("lane", it.first)
                .tag("phase", it.second)
                .register(registry)
        }.increment()
    }

    /**
     * worker은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param backlog 업무 처리에 필요한 backlog 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param oldestAge 업무 처리에 필요한 oldestAge 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param checkpoint 업무 처리에 필요한 checkpoint 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun worker(kind: WorkerKind, backlog: Long, oldestAge: Duration, checkpoint: Long) {
        workerBacklog.getValue(kind).set(backlog.coerceAtLeast(0))
        workerOldestAge.getValue(kind).set(oldestAge.seconds.coerceAtLeast(0))
        workerCheckpoint.getValue(kind).set(checkpoint.coerceAtLeast(0))
    }

    /**
     * sseSubscribers은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param count 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun sseSubscribers(count: Int) {
        activeSse.set(count.coerceAtLeast(0))
    }

    /**
     * sseReset은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun sseReset() {
        sseResets.incrementAndGet()
    }

    /**
     * eligiblePoolRatio은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param ratio 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun eligiblePoolRatio(ratio: Double) {
        poolEligibleRatio.set((ratio.coerceIn(0.0, 1.0) * RATIO_SCALE).toLong())
    }

    /**
     * degraded은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param component 업무 처리에 필요한 component 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun degraded(component: VoucherPoolHealthComponent) {
        degradedComponents.computeIfAbsent(component.name) {
            Counter.builder("voucher.pool.degraded")
                .tag("component", it)
                .register(registry)
        }.increment()
    }

    /**
     * quarantine은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param count 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun quarantine(count: Long) {
        quarantineCount.set(count.coerceAtLeast(0))
    }

    /**
     * purgeLag은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param age 업무 처리에 필요한 age 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun purgeLag(age: Duration) {
        purgeLagSeconds.set(age.seconds.coerceAtLeast(0))
    }

    /**
     * restoreFailed은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun restoreFailed() {
        restoreFailures.incrementAndGet()
    }

    /**
     * gauge은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param baseUnit 업무 처리에 필요한 baseUnit 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tags 업무 처리에 필요한 tags 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun gauge(
        name: String,
        value: AtomicInteger,
        baseUnit: String? = null,
        vararg tags: String,
    ) {
        /**
         * `builder`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val builder = Gauge.builder(name, value) { it.get().toDouble() }.tags(*tags)
        baseUnit?.let(builder::baseUnit)
        builder.register(registry)
    }

    /**
     * gauge은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param baseUnit 업무 처리에 필요한 baseUnit 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tags 업무 처리에 필요한 tags 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun gauge(
        name: String,
        value: AtomicLong,
        baseUnit: String? = null,
        vararg tags: String,
    ) {
        /**
         * `builder`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val builder = Gauge.builder(name, value) { it.get().toDouble() }.tags(*tags)
        baseUnit?.let(builder::baseUnit)
        builder.register(registry)
    }
    /**
     * MetricKey은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property first 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     * @property second 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     */


    private class MetricKey(val first: String, val second: String) {

        /**
         * equals은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param other 업무 처리에 필요한 other 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        override fun equals(other: Any?): Boolean =
            other is MetricKey &&
                    first == other.first &&
                    second == other.second

        /**
         * hashCode은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        override fun hashCode(): Int = hashOf(first, second)

        /**
         * toString은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        override fun toString(): String = "MetricKey(first='$first', second='$second')"
    }
}
