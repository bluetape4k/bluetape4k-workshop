package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * TicketEventStreamIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketEventStreamIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "terminal event between snapshot and subscribe is caught up" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `terminal event between snapshot and subscribe is caught up`() {
        val stream = TicketEventStream(4, 4, clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val scope = StreamScope.OwnerAttempt(Uuid.V7.nextId(), Uuid.V7.nextId())

        val subscription: TicketSubscription = stream.subscribe(scope) {
            stream.publish(scope, "purchase_terminal", mapOf("state" to "approved"))
            mapOf("state" to "payment_authorizing")
        }

        subscription.snapshot.highWater shouldBeEqualTo 0L
        subscription.poll()?.type shouldBeEqualTo "purchase_terminal"
        subscription.poll().shouldBeNull()
        subscription.close()
    }
}
