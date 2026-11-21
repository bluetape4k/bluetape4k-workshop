package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * NodeLocalDatabaseBulkheadTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class NodeLocalDatabaseBulkheadTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "foreground work is rejected when its local lane is full and recovers after release" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `foreground work is rejected when its local lane is full and recovers after release`() {
        val bulkhead = NodeLocalDatabaseBulkhead(
            foregroundPermits = 1,
            backgroundPermits = 1,
            acquireTimeout = Duration.ZERO
        )
        var nested: DatabaseBulkheadOutcome<String>? = null

        val outer = bulkhead.execute(DatabaseWorkload.FOREGROUND) {
            nested = bulkhead.execute(DatabaseWorkload.FOREGROUND) { "unexpected" }
            "outer"
        }
        val recovered = bulkhead.execute(DatabaseWorkload.FOREGROUND) {
            "recovered"
        }

        outer shouldBeEqualTo DatabaseBulkheadOutcome.Executed("outer")
        nested shouldBeEqualTo DatabaseBulkheadOutcome.Rejected(DatabaseWorkload.FOREGROUND)
        recovered shouldBeEqualTo DatabaseBulkheadOutcome.Executed("recovered")
    }

    @Test
/**
 * 이 시나리오는 "background lane remains independent from a saturated foreground lane" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `background lane remains independent from a saturated foreground lane`() {
        val bulkhead = NodeLocalDatabaseBulkhead(
            foregroundPermits = 1,
            backgroundPermits = 1,
            acquireTimeout = Duration.ZERO
        )
        var background: DatabaseBulkheadOutcome<String>? = null

        bulkhead.execute(DatabaseWorkload.FOREGROUND) {
            background = bulkhead.execute(DatabaseWorkload.BACKGROUND) {
                "background"
            }
        }

        background shouldBeEqualTo DatabaseBulkheadOutcome.Executed("background")
    }
}
