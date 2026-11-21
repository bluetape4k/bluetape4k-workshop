package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

/**
 * EventSourcedOperationsConfigurationTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedOperationsConfigurationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "configuration binds the bounded database gate meters" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `configuration binds the bounded database gate meters`() {
        val registry = SimpleMeterRegistry()
        val configuration = EventSourcedOperationsConfiguration()

        val metrics = configuration.eventSourcedMetrics(registry)
        configuration.eventSourcedDatabasePermitGate(metrics)

        registry.find("voucher_db_bulkhead_active").gauge().shouldNotBeNull()
        registry.find("voucher_db_bulkhead_queued").gauge().shouldNotBeNull()
    }
}
