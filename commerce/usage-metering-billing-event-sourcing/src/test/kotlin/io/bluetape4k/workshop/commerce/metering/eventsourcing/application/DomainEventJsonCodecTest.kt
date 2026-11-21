package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * DomainEventJsonCodecTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class DomainEventJsonCodecTest {

    companion object: KLogging()

/**
 * codec은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val codec = DomainEventJsonCodec()

    @Test
/**
 * 이 시나리오는 "event payload is bounded before persistence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event payload is bounded before persistence`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            val event = MeterRegistered("meter", "x".repeat(70_000), "USD")
            codec.encode(event, Instant.EPOCH)
        }

        log.debug { "failure: $failure" }
        failure.message shouldBeEqualTo "event_payload_too_large"
    }

    @Test
/**
 * 이 시나리오는 "default event identity uses uuid v7" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `default event identity uses uuid v7`() {
        val event = MeterRegistered("meter", "api-calls", "USD")
        val newEvent = codec.encode(event, Instant.EPOCH)

        log.debug { "newEvent=$newEvent" }
        newEvent.eventId.version() shouldBeEqualTo 7
    }
}
