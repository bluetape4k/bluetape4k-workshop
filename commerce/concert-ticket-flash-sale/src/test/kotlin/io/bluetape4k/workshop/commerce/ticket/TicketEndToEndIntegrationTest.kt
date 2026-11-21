package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.FakeTicketProvider
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.TicketEffectWorker
import org.junit.jupiter.api.Test

/**
 * waiting-room grant를 소비한 구매가 payment 승인과 ticket 발급 effect를 거쳐
 * 하나의 durable sold 결과로 수렴하는지 확인하는 대표 통합 테스트입니다.
 *
 * Fake provider를 사용해 외부 호출을 결정적으로 완료하고 purchase state, ticket state,
 * inventory invariant, duplicate effect 부재를 함께 검증합니다. 따라서 admission
 * grant가 inventory를 대신한다거나 provider callback을 두 번 처리해도 ticket을 두 번
 * 발급한다는 가정을 허용하지 않습니다. 실제 PSP와 issuer 네트워크 장애는 별도
 * reconciliation test의 범위입니다.
 */
internal class TicketEndToEndIntegrationTest : AbstractTicketIntegrationTest() {

    companion object: KLogging()
    
    @Test
/**
 * 이 시나리오는 "admitted purchase converges through payment and one ticket issue" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `admitted purchase converges through payment and one ticket issue`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)
            val payments = FakePaymentProvider()
                .apply { complete(command.authorizationOperationId, PaymentOutcome.APPROVED) }

            PaymentWorker(fixture.executor, fixture.service, payments)
                .run(command.authorizationOperationId)

            val effectId = fixture.issueEffectOperationId()

            TicketEffectWorker(fixture.executor, fixture.service, FakeTicketProvider())
                .run(effectId)

            fixture.purchaseAttemptState(command.attemptId).shouldBeEqualTo("approved")

            fixture.ticketState().shouldBeEqualTo("issued")
            payments.authorizationCount(command.authorizationOperationId) shouldBeEqualTo 0

            fixture.assertInventoryInvariant()
            fixture.assertNoDuplicateEffects()
        }
    }
}
