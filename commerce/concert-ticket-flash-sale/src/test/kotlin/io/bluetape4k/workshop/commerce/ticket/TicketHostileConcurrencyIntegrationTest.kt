package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * TicketHostileConcurrencyIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketHostileConcurrencyIntegrationTest: AbstractTicketIntegrationTest() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "many buyers racing for the last seats never oversell" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `many buyers racing for the last seats never oversell`() {
        PurchaseFixture(inventory = 4).use { fixture ->
            val commands = List(24) { fixture.command(ip = Uuid.V7.nextId()) }

            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                commands
                    .map { command ->
                        executor.submit {
                            runCatching { fixture.service.start(command) }
                        }
                    }
                    .forEach { future ->
                        future.get()
                    }
            }
            fixture.assertInventoryInvariant()
            fixture.assertNoDuplicateEffects()
        }
    }
}
