package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitMetrics
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
 * `VoucherLeaderState`은 수명주기 상태입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 * @property metricValue 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal enum class VoucherLeaderState(val metricValue: Int) {
    /**
     * `VoucherLeaderState.UNKNOWN`은 외부 결과가 아직 확정되지 않아 성공이나 실패로 단정할 수 없는 값입니다.
     */
    UNKNOWN(0),
    /**
     * `VoucherLeaderState.SKIPPED`은 `skipped` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    SKIPPED(1),
    /**
     * `VoucherLeaderState.ELECTED`은 `elected` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    ELECTED(2),
    /**
     * `VoucherLeaderState.DEGRADED`은 보조 의존성이 제한되지만 권위 데이터만으로 일부 기능을 계속할 수 있는 값입니다.
     */
    DEGRADED(3),
}

/**
 * dashboard와 alert가 사용하는 bounded-cardinality operational contract를 등록합니다.
 * @property registry 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
internal class VoucherMetrics(
    /**
     * `registry`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val registry: MeterRegistry,
): DatabasePermitMetrics {

    private companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val BOUNDED_VALUE = Regex("[A-Z][A-Z0-9_]{0,31}")

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
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */

    private val commandTimers = ConcurrentHashMap<MetricKey, Timer>()
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val databaseRejections = ConcurrentHashMap<String, Counter>()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val redisDegradations = ConcurrentHashMap<String, Counter>()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val sseRejections = ConcurrentHashMap<String, Counter>()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val openReviews = AtomicInteger()
    /**
     * `oldestBacklogSeconds`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val oldestBacklogSeconds = AtomicLong()
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    private val workerLastSuccessEpochSeconds = AtomicLong()
    /**
     * `workerAttempts`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val workerAttempts = AtomicLong()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val activeSse = AtomicInteger()
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    private val leaderState = AtomicInteger(VoucherLeaderState.UNKNOWN.metricValue)

    init {
        Gauge.builder("voucher.review.open", openReviews) { it.get().toDouble() }
            .register(registry)
        Gauge.builder("voucher.backlog.oldest.age", oldestBacklogSeconds) { it.get().toDouble() }
            .baseUnit("seconds")
            .register(registry)
        Gauge.builder("voucher.worker.last.success", workerLastSuccessEpochSeconds) { it.get().toDouble() }
            .baseUnit("seconds")
            .register(registry)
        Gauge.builder("voucher.worker.attempts", workerAttempts) { it.get().toDouble() }
            .register(registry)
        Gauge.builder("voucher.sse.active", activeSse) { it.get().toDouble() }
            .register(registry)
        Gauge.builder("voucher.leader.state", leaderState) { it.get().toDouble() }
            .register(registry)
    }

    /**
     * recordCommand은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun recordCommand(
        operation: String,
        outcome: String,
        duration: Duration,
    ) {
        val key = MetricKey(bounded(operation), bounded(outcome))

        commandTimers.computeIfAbsent(key) {
            Timer.builder("voucher.command.duration")
                .tag("operation", key.operation)
                .tag("outcome", key.outcome)
                .register(registry)
        }.record(duration)
    }

    /**
     * databaseRejected은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun databaseRejected(lane: String) {
        databaseRejections.computeIfAbsent(bounded(lane)) {
            Counter.builder("voucher.db.bulkhead.rejected")
                .tag("admission", it)
                .register(registry)
        }.increment()
    }

    /**
     * rejected은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun rejected(lane: DatabaseLane) {
        databaseRejected(lane.name)
    }

    /**
     * redisDegraded은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param backend 업무 처리에 필요한 backend 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun redisDegraded(backend: String) {
        redisDegradations.computeIfAbsent(bounded(backend)) {
            Counter.builder("voucher.redis.degraded")
                .tag("backend", it)
                .register(registry)
        }.increment()
    }

    /**
     * reviewOpen은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param count 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun reviewOpen(count: Int) {
        openReviews.set(count.coerceAtLeast(0))
    }

    /**
     * backlogOldestAge은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param age 업무 처리에 필요한 age 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun backlogOldestAge(age: Duration) {
        oldestBacklogSeconds.set(age.seconds.coerceAtLeast(0))
    }

    /**
     * workerSucceeded은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param epochSeconds 업무 처리에 필요한 epochSeconds 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param attempts 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun workerSucceeded(
        epochSeconds: Long,
        attempts: Int,
    ) {
        workerLastSuccessEpochSeconds.set(epochSeconds.coerceAtLeast(0))
        workerAttempts.addAndGet(attempts.coerceAtLeast(0).toLong())
    }

    /**
     * sseOpened은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun sseOpened() {
        activeSse.incrementAndGet()
    }

    /**
     * sseClosed은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun sseClosed() {
        activeSse.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /**
     * sseRejected은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun sseRejected(reason: String) {
        sseRejections.computeIfAbsent(bounded(reason)) {
            Counter.builder("voucher.sse.rejected")
                .tag("reason", it)
                .register(registry)
        }.increment()
    }

    /**
     * leaderState은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun leaderState(state: VoucherLeaderState) {
        leaderState.set(state.metricValue)
    }
/**
 * MetricKey은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property operation 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property outcome 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 */


    private data class MetricKey(val operation: String, val outcome: String)
}
