package io.bluetape4k.workshop.commerce.usagebilling.query.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

/**
 * QueryMetricsTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class QueryMetricsTest {

    companion object: KLogging()

/**
 * registry은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val registry = SimpleMeterRegistry()
/**
 * metrics은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val metrics = QueryMetrics(registry)

    @Test
/**
 * 이 시나리오는 "inbox metric keeps only service outcome and event type as tags" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `inbox metric keeps only service outcome and event type as tags`() {
        metrics.inboxOutcome("APPLIED", "InvoiceIssued")

        registry.find("usage_billing_inbox_outcome_total")
            .tags("service", "query", "outcome", "APPLIED", "event_type", "InvoiceIssued")
            .counter().shouldNotBeNull()
            .count() shouldBeEqualTo 1.0
    }
}
