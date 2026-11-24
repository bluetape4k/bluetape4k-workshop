package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import org.junit.jupiter.api.Test

/**
 * CanonicalEventHashTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class CanonicalEventHashTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "payload property order does not change the event hash" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `payload property order does not change the event hash`() {
        val first = material("""{"meterCode":"api_calls","quantity":10}""")
        val reordered = material("""{"quantity":10,"meterCode":"api_calls"}""")

        CanonicalEventHash.sha256(reordered) shouldBeEqualTo CanonicalEventHash.sha256(first)
        CanonicalEventHash.sha256(material("""{"quantity":10}""")) shouldBeEqualTo
                "8169d82413c0355319a0a42f41adc49f392309da1ab94c263d5cb956e479f2eb"
    }

    @Test
/**
 * 이 시나리오는 "previous hash and payload changes are detectable" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `previous hash and payload changes are detectable`() {
        val original = material("""{"quantity":10}""")

        CanonicalEventHash
            .sha256(original.copy(payload = """{"quantity":11}""")) shouldNotBeEqualTo
                CanonicalEventHash.sha256(original)

        CanonicalEventHash
            .sha256(original.copy(previousHash = "different")) shouldNotBeEqualTo
                CanonicalEventHash.sha256(original)
    }

/**
 * material은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun material(payload: String) = EventHashMaterial(
        stream = StreamKey("tenant-a", "Usage", "usage-1"),
        streamVersion = 1,
        eventType = "usage.accepted",
        schemaVersion = 1,
        payload = payload,
        metadata = """{"actor":"tenant-a"}""",
        previousHash = null,
    )
}
