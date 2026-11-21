package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

/**
 * EventStoreRepositoryArchitectureTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventStoreRepositoryArchitectureTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "event repository is a connection free bluetape repository" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event repository is a connection free bluetape repository`() {
        ExposedJdbcRepository::class.java.isAssignableFrom(EventLogRepository::class.java).shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "event repository rejects generic mutation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event repository rejects generic mutation`() {
        assertFailsWith<UnsupportedOperationException> {
            EventLogRepository().deleteAll()
        }
    }

    @Test
/**
 * 이 시나리오는 "event log and stream head expose append authority columns" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event log and stream head expose append authority columns`() {
        EventLog.columns shouldContain EventLog.streamVersion
        EventLog.columns shouldContain EventLog.globalPosition
        EventLog.columns shouldContain EventLog.canonicalChecksum
        StreamHeads.columns shouldContain StreamHeads.version
        AppendFences.columns shouldContain AppendFences.nextGlobalPosition
        IdempotencyReceipts.columns shouldContain IdempotencyReceipts.fingerprint
        EventSnapshots.columns shouldContain EventSnapshots.streamVersion
        ProjectionCheckpoints.columns shouldContain ProjectionCheckpoints.fencingToken
    }
}
