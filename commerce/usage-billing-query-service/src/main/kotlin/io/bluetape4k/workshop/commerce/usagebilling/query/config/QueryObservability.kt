package io.bluetape4k.workshop.commerce.usagebilling.query.config

import io.bluetape4k.javatimes.minutes
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryRecoveryService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
/**
 * QueryProperties은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property maxQuarantineBacklog 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야
 * 합니다.
 * @property maxOldestQuarantineAge 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 */


@ConfigurationProperties("usage-billing.query")
data class QueryProperties(
    /**
     * `maxQuarantineBacklog`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxQuarantineBacklog: Long = 0,
    /**
     * `maxOldestQuarantineAge`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxOldestQuarantineAge: Duration = DEFAULT_MAX_OLDEST_QUARANTINE_AGE,
): Serializable {
    private companion object {
        /**
         * `serialVersionUID`은 사용량 과금·정산에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `DEFAULT_MAX_OLDEST_QUARANTINE_AGE_MINUTES`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val DEFAULT_MAX_OLDEST_QUARANTINE_AGE_MINUTES = 15L
/**
 * `DEFAULT_MAX_OLDEST_QUARANTINE_AGE`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private val DEFAULT_MAX_OLDEST_QUARANTINE_AGE: Duration =
    DEFAULT_MAX_OLDEST_QUARANTINE_AGE_MINUTES.minutes()
/**
 * QueryMetrics은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property registry 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class QueryMetrics(
    /**
     * `registry`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val registry: MeterRegistry,
) {
    private companion object: KLogging() {
        /**
         * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val OUTCOMES = setOf("APPLIED", "DUPLICATE", "DEFERRED", "QUARANTINED")
        /**
         * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val REDRIVE_OUTCOMES = setOf("REQUESTED", "NOT_FOUND")
        /**
         * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val EVENT_TYPES = setOf(
            "PriceActivated",
            "UsageAccepted",
            "UsageCorrected",
            "ChargeRated",
            "AdjustmentPosted",
            "InvoiceIssued",
            "InvoiceCorrectionIssued",
        )
    }

    /**
     * inboxOutcome은 사용량 계량·청구 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    fun inboxOutcome(outcome: String, eventType: String) {
        check(outcome in OUTCOMES) { "unsupported_query_inbox_outcome:$outcome" }
        check(eventType in EVENT_TYPES) { "unsupported_query_event_type:$eventType" }
        Counter.builder("usage_billing_inbox_outcome_total")
            .tag("service", "query")
            .tag("outcome", outcome)
            .tag("event_type", eventType)
            .register(registry)
            .increment()
    }

    /**
     * redrive은 사용량 계량·청구의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun redrive(outcome: String) {
        check(outcome in REDRIVE_OUTCOMES) { "unsupported_query_redrive_outcome:$outcome" }
        Counter.builder("usage_billing_redrive_total")
            .tag("service", "query")
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

}
/**
 * QueryRecoveryHealthIndicator은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property recovery 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property properties 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component("usageBillingQueryRecovery")
class QueryRecoveryHealthIndicator(
    /**
     * `recovery`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val recovery: QueryRecoveryService,
    /**
     * `properties`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: QueryProperties,
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
    override fun health(): Health {
        val snapshot = recovery.snapshot()
        val oldestAge = snapshot.oldestQuarantineAt?.let { Duration.between(it, Instant.now(clock)) }
        val degraded = snapshot.quarantineCount > properties.maxQuarantineBacklog ||
                (oldestAge != null && oldestAge > properties.maxOldestQuarantineAge)

        return (if (degraded) Health.down() else Health.up())
            .withDetail("quarantineCount", snapshot.quarantineCount)
            .withDetail("oldestQuarantineAgeSeconds", oldestAge?.seconds ?: 0)
            .build()
    }
}
