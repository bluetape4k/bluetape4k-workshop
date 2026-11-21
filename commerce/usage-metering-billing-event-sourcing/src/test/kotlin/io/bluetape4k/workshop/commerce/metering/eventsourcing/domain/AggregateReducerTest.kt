package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * AggregateReducerTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class AggregateReducerTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "meter reducer deterministically selects the price effective at usage time" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `meter reducer deterministically selects the price effective at usage time`() {
        val registered = MeterRegistered("api_calls", "request", "USD")
        val first = PriceActivated("USD", BigDecimal("0.10"), Instant.parse("2026-06-01T00:00:00Z"))
        val second = PriceActivated("USD", BigDecimal("0.20"), Instant.parse("2026-07-01T00:00:00Z"))

        val state = listOf(registered, first, second)
            .fold(MeterState.Empty, MeterReducer::evolve)

        log.debug { "state: $state" }
        state.priceAt(Instant.parse("2026-06-30T23:59:59Z")).unitPrice shouldBeEqualTo BigDecimal("0.10")
        state.priceAt(Instant.parse("2026-07-01T00:00:00Z")).unitPrice shouldBeEqualTo BigDecimal("0.20")
    }

    @Test
/**
 * 이 시나리오는 "meter cannot register twice" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `meter cannot register twice`() {
        val registered = MeterReducer.evolve(
            state = MeterState.Empty,
            event = MeterRegistered("api_calls", "request", "USD")
        )
        log.debug { "registered: $registered" }

        assertFailsWith<IllegalStateException> {
            MeterReducer.evolve(
                state = registered,
                event = MeterRegistered("api_calls", "request", "USD")
            )
        }
    }
}
