package io.bluetape4k.workshop.commerce.ticket.ticketing

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.FakeTicketProvider
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.TicketEffectWorker
import org.junit.jupiter.api.Test

/**
 * TicketEffectIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketEffectIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "response loss and duplicate delivery issue one external ticket and one receipt" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `response loss and duplicate delivery issue one external ticket and one receipt`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)
            val payments = FakePaymentProvider().apply {
                complete(command.authorizationOperationId, PaymentOutcome.APPROVED)
            }
            PaymentWorker(fixture.executor, fixture.service, payments).run(command.authorizationOperationId)
            val operationId = fixture.issueEffectOperationId()
            val provider = FakeTicketProvider().apply { succeedButLoseResponse(operationId) }
            val worker = TicketEffectWorker(fixture.executor, fixture.service, provider)

            worker.run(operationId).shouldBeTrue()
            fixture.expireEffectClaim(operationId)
            worker.run(operationId).shouldBeTrue()
            worker.run(operationId).shouldBeFalse()

            provider.issueCount(operationId) shouldBeEqualTo 1
            fixture.ticketState() shouldBeEqualTo "issued"
            fixture.ticketDisposition() shouldBeEqualTo "issued"
            fixture.effectReceiptCount(operationId).shouldBeEqualTo(1)
        }
    }
}
