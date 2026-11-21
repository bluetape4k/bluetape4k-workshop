package io.bluetape4k.workshop.commerce.metering.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.domain.CommandReceiptStatus
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriods
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRuns
import io.bluetape4k.workshop.commerce.metering.persistence.CommandReceipts
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationRuns
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * `NO_AGE_RECORDED`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
 *
 * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
 */
private const val NO_AGE_RECORDED = -1L
/**
 * MeteringHealthIndicator은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component("meteringAuthority")
class MeteringHealthIndicator(
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
): HealthIndicator {

    companion object: KLogging()

    /**
     * health은 사용량 계량·청구의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Transactional(readOnly = true)
    override fun health(): Health {
        val now = clock.instant()
        val oldestClosing = BillingPeriods
            .selectAll()
            .where { BillingPeriods.state eq BillingPeriodState.CLOSING.name }
            .orderBy(BillingPeriods.createdAt to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
        val hasUnpriced = CloseRuns
            .selectAll()
            .where { CloseRuns.unpricedCount greater 0L }
            .limit(1)
            .any()
        val staleReceipt = CommandReceipts
            .selectAll()
            .where {
                (CommandReceipts.status eq CommandReceiptStatus.IN_PROGRESS.name) and
                        (CommandReceipts.leaseDeadline less now)
            }
            .limit(1)
            .any()
        val lastReconciliation = ReconciliationRuns
            .selectAll()
            .where { ReconciliationRuns.completedAt.isNotNull() }
            .orderBy(ReconciliationRuns.completedAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(ReconciliationRuns.completedAt)

        return Health.up()
            .withDetail(
                "oldestClosingAgeSeconds",
                oldestClosing?.let { Duration.between(it[BillingPeriods.createdAt], now).seconds } ?: NO_AGE_RECORDED,
            )
            .withDetail("hasUnpricedUsage", hasUnpriced)
            .withDetail("hasStaleReceipt", staleReceipt)
            .withDetail(
                "lastReconciliationAgeSeconds",
                lastReconciliation?.let { Duration.between(it, now).seconds } ?: NO_AGE_RECORDED,
            )
            .build()
    }
}
