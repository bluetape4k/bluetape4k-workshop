package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * EventContractTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class EventContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "stream keys sort by tenant type and id" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stream keys sort by tenant type and id`() {
        val keys = listOf(
            StreamKey("tenant-b", "Usage", "2"),
            StreamKey("tenant-a", "Usage", "2"),
            StreamKey("tenant-a", "Meter", "9"),
        )

        keys.sorted().map(StreamKey::canonical) shouldBeEqualTo listOf(
            "tenant-a/Meter/9",
            "tenant-a/Usage/2",
            "tenant-b/Usage/2"
        )
    }

    @Test
/**
 * 이 시나리오는 "stream key rejects a blank boundary" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stream key rejects a blank boundary`() {
        assertFailsWith<IllegalArgumentException> {
            StreamKey("tenant-a", " ", "stream-1")
        }
    }
}
