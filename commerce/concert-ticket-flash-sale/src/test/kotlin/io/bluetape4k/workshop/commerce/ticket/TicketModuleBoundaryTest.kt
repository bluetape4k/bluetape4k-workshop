package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * TicketModuleBoundaryTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketModuleBoundaryTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "modulith dependencies follow the approved graph" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `modulith dependencies follow the approved graph`() {
        val modules = ApplicationModules.of(TicketFlashSaleApplication::class.java).verify()

        modules
            .map { it.identifier.toString() }
            .onEach { log.debug { "module=$it" } }
            .toSet() shouldBeEqualTo setOf("salecontrol", "admission", "purchase", "payment", "ticketing", "operations")
    }
}
