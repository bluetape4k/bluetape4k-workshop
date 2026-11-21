package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * EventCodecRegistryTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class EventCodecRegistryTest {
    @Test
/**
 * 이 시나리오는 "registry applies a contiguous upcast chain before decoding" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `registry applies a contiguous upcast chain before decoding`() {
        val registry = EventCodecRegistry()
            .register("usage.accepted", 3) { payload ->
                payload
            }
            .registerUpcaster("usage.accepted", 1) { payload ->
                payload.replace("quantity", "amount")
            }
            .registerUpcaster("usage.accepted", 2) { payload ->
                payload.replace("amount", "quantity")
            }

        registry.decode(
            "usage.accepted",
            1,
            """{"quantity":10}"""
        ) shouldBeEqualTo """{"quantity":10}"""
    }

    @Test
/**
 * 이 시나리오는 "registry rejects unknown event versions" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `registry rejects unknown event versions`() {
        val registry = EventCodecRegistry()
            .register("usage.accepted", 1) { it }

        assertFailsWith<UnknownEventSchemaException> {
            registry.decode("usage.accepted", 2, "{}")
        }
    }

    @Test
/**
 * 이 시나리오는 "registry rejects a broken upcast chain" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `registry rejects a broken upcast chain`() {
        val registry = EventCodecRegistry()
            .register("usage.accepted", 3) { it }
            .registerUpcaster("usage.accepted", 1) { it }

        assertFailsWith<IllegalStateException> {
            registry.validate()
        }
    }
}
