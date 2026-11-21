package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture

/**
 * AbstractTicketIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal abstract class AbstractTicketIntegrationTest {

    companion object: KLogging()

/**
 * assertInventoryInvariant은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun PurchaseFixture.assertInventoryInvariant() {
        val inventory = inventoryQuantities()

        inventory.held shouldBeGreaterOrEqualTo 0
        inventory.sold shouldBeGreaterOrEqualTo 0
        inventory.total shouldBeGreaterOrEqualTo (inventory.held + inventory.sold)
    }

/**
 * assertNoDuplicateEffects은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun PurchaseFixture.assertNoDuplicateEffects() {
        duplicatePaymentOperationCount() shouldBeEqualTo 0
        duplicateEffectReceiptCount() shouldBeEqualTo 0
    }
}
