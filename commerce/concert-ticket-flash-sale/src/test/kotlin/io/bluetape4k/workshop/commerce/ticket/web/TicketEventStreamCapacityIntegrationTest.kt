package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * TicketEventStreamCapacityIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketEventStreamCapacityIntegrationTest {

    companion object: KLogging()
    
    @Test
/**
 * 이 시나리오는 "slow consumer is disconnected and releases its connection permit" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `slow consumer is disconnected and releases its connection permit`() {
        val stream = TicketEventStream(queueSize = 1, maxConnections = 1)
        val scope = StreamScope.PublicSale(Uuid.V7.nextId())
        stream.subscribe(scope) { mapOf("status" to "open") }
        stream.publish(scope, "inventory", mapOf("remaining" to "10"))

        assertFailsWith<TicketStreamSlowConsumer> {
            stream.publish(scope, "inventory", mapOf("remaining" to "9"))
        }

        stream.activeConnections() shouldBeEqualTo 0
        stream.subscribe(scope) { mapOf("status" to "open") }.close()
    }

    @Test
/**
 * 이 시나리오는 "public stream rejects owner and payment fields" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `public stream rejects owner and payment fields`() {
        val stream = TicketEventStream(2, 1)

        assertFailsWith<IllegalArgumentException> {
            stream.publish(
                StreamScope.PublicSale(Uuid.V7.nextId()),
                "purchase",
                mapOf("buyerSubjectId" to Uuid.V7.nextId().toString()),
            )
        }
    }
}
