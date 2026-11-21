package io.bluetape4k.workshop.commerce.metering.eventsourcing.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.BillingTelemetry
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionFailureRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionTelemetry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
/**
 * EventSourcingMetrics은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property registry 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class EventSourcingMetrics(
    /**
     * `registry`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val registry: MeterRegistry,
): BillingTelemetry, ProjectionTelemetry {

    companion object: KLogging()

    /**
     * recordAppend은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun recordAppend(outcome: String, duration: Duration) {
        timer("billing.event.append", "outcome", outcome).record(duration)
    }

    /**
     * recordReplay은 사용량 계량·청구의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param eventCount 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    override fun recordReplay(outcome: String, eventCount: Int, duration: Duration) {
        registry.counter("billing.event.replay", "outcome", outcome).increment()
        registry.summary("billing.event.replay.events", "outcome", outcome).record(eventCount.toDouble())
        timer("billing.event.replay.duration", "outcome", outcome).record(duration)
    }

    /**
     * recordSnapshotFallback은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun recordSnapshotFallback(reason: String) {
        registry.counter("billing.snapshot.fallback", "reason", reason).increment()
    }

    /**
     * recordProjection은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param lag 업무 처리에 필요한 lag 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun recordProjection(outcome: String, lag: Long) {
        registry.counter("billing.projection.batch", "outcome", outcome).increment()
        registry.summary("billing.projection.lag", "outcome", outcome).record(lag.toDouble())
    }

    /**
     * recordRebuild은 사용량 계량·청구의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    override fun recordRebuild(outcome: String) {
        registry.counter("billing.projection.rebuild", "outcome", outcome).increment()
    }

    /**
     * recordQuarantine은 사용량 계량·청구의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게
     * 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    fun recordQuarantine(eventType: String) {
        registry.counter("billing.projection.quarantine", "event.type", eventType).increment()
    }

    /**
     * recordCloseBatch은 사용량 계량·청구의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param size 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    override fun recordCloseBatch(outcome: String, size: Int) {
        registry.counter("billing.close.batch", "outcome", outcome).increment()
        registry.summary("billing.close.batch.size", "outcome", outcome).record(size.toDouble())
    }

    /**
     * recordReconciliation은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun recordReconciliation(kind: String) {
        registry.counter("billing.reconciliation.finding", "kind", kind).increment()
    }

    /**
     * timer은 사용량 계량·청구 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tagName 업무 처리에 필요한 tagName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tagValue 업무 처리에 필요한 tagValue 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun timer(name: String, tagName: String, tagValue: String): Timer =
        registry.timer(name, tagName, tagValue)

    /**
     * record은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun Timer.record(duration: Duration) {
        record(duration)
    }
}
/**
 * EventSourcingHealthIndicator은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property eventStore 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property generations 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property failures 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class EventSourcingHealthIndicator(
    /**
     * `eventStore`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventStore: EventStoreRepository,
    /**
     * `generations`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val generations: ProjectionGenerationRepository,
    /**
     * `failures`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val failures: ProjectionFailureRepository,
    transactionManager: PlatformTransactionManager,
): HealthIndicator {

    private companion object: KLogging() {
        /**
         * `BILLING_PROJECTION`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        const val BILLING_PROJECTION = "billing"
        /**
         * `CONNECTIVITY_PAGE_SIZE`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val CONNECTIVITY_PAGE_SIZE = 1
    }
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val transactions = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /**
     * health은 사용량 계량·청구의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health = runCatching {
        checkNotNull(transactions.execute { calculateHealth() })
    }.getOrElse { failure ->
        Health.down().withDetail("reason", "event_store_unavailable")
            .withException(failure)
            .build()
    }

    /**
     * calculateHealth은 사용량 계량·청구의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun calculateHealth(): Health {
        eventStore.loadAfterGlobalPosition(0, CONNECTIVITY_PAGE_SIZE)
        val active = generations.active(BILLING_PROJECTION)
            ?: return Health.down().withDetail("reason", "active_projection_missing").build()

        val lag = (active.highWatermark - active.checkpoint).coerceAtLeast(0)
        val failure = failures.latest(BILLING_PROJECTION, active.generation)
        val builder = if (failure == null) Health.up() else Health.down()

        return builder
            .withDetail("activeGeneration", active.generation)
            .withDetail("projectionPosition", active.checkpoint)
            .withDetail("projectionLag", lag)
            .withDetail("projectionState", active.state.name)
            .withDetail("quarantined", failure != null)
            .apply {
                failure?.let {
                    withDetail("failedPosition", it.globalPosition)
                    withDetail("failureAttempts", it.attemptCount)
                }
            }
            .build()
    }
}
