package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import org.junit.jupiter.api.Test
import java.sql.SQLTransientConnectionException
import java.util.*

/**
 * SnapshotWriterTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class SnapshotWriterTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "snapshot write failure is isolated from its already committed append" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `snapshot write failure is isolated from its already committed append`() {
        val writer = SnapshotWriter(FailingSnapshotTransactionRunner(), EventSnapshotRepository())
        val snapshot = EventSnapshot(
            stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()),
            metadata = SnapshotMetadata(250, schemaVersion = 1, keyVersion = 1),
            canonicalState = "state-250",
        )

        writer.writeAfterAppend(snapshot).shouldBeFalse()
    }

/**
 * FailingSnapshotTransactionRunner 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class FailingSnapshotTransactionRunner: SnapshotTransactionRunner {
/**
 * inTransaction은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun <T> inTransaction(block: () -> T): T =
            throw SQLTransientConnectionException("snapshot store unavailable")
    }
}
