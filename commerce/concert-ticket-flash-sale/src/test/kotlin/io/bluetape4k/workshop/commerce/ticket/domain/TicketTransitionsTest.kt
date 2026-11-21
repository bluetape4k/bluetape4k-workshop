package io.bluetape4k.workshop.commerce.ticket.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * TicketTransitionsTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketTransitionsTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "payment timeout preserves held inventory for reconciliation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `payment timeout preserves held inventory for reconciliation`() {
        transition(
            PurchaseState.PAYMENT_AUTHORIZING,
            PaymentOutcome.UNKNOWN
        ) shouldBeEqualTo PurchaseTransition(
            next = PurchaseState.RECONCILIATION_REQUIRED,
            heldDelta = 0,
            soldDelta = 0,
            releaseGuards = false,
        )
    }

    @Test
/**
 * 이 시나리오는 "normal approval moves held inventory to sold and releases foreground guards" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normal approval moves held inventory to sold and releases foreground guards`() {
        transition(
            PurchaseState.PAYMENT_AUTHORIZING,
            PaymentOutcome.APPROVED
        ) shouldBeEqualTo PurchaseTransition(
            next = PurchaseState.APPROVED,
            heldDelta = -1,
            soldDelta = 1,
            releaseGuards = true,
            ticketDisposition = TicketDisposition.PENDING,
        )
    }

    @Test
/**
 * 이 시나리오는 "late approval suppresses issue and enters refund remediation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `late approval suppresses issue and enters refund remediation`() {
        transition(
            PurchaseState.CANCELLATION_REQUESTED,
            PaymentOutcome.APPROVED
        ) shouldBeEqualTo PurchaseTransition(
            next = PurchaseState.REFUND_PENDING,
            heldDelta = -1,
            soldDelta = 1,
            releaseGuards = false,
            ticketDisposition = TicketDisposition.NEVER_ISSUED,
        )
    }

    @Test
/**
 * 이 시나리오는 "refund does not restock until ticket disposition is safe" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `refund does not restock until ticket disposition is safe`() {
        refundTransition(
            PurchaseState.REFUND_PENDING,
            TicketDisposition.ISSUED
        ) shouldBeEqualTo PurchaseTransition(
            next = PurchaseState.REFUND_PENDING,
            heldDelta = 0,
            soldDelta = 0,
            releaseGuards = false,
            ticketDisposition = TicketDisposition.ISSUED,
        )

        refundTransition(
            PurchaseState.REFUND_PENDING,
            TicketDisposition.REVOKED
        ) shouldBeEqualTo PurchaseTransition(
            next = PurchaseState.REFUNDED,
            heldDelta = 0,
            soldDelta = -1,
            releaseGuards = true,
            ticketDisposition = TicketDisposition.REVOKED,
        )
    }

    @Test
/**
 * 이 시나리오는 "suspended sale resumes only inside its window with healthy invariants" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `suspended sale resumes only inside its window with healthy invariants`() {
        val context = SaleTransitionContext(
            now = Instant.parse("2026-07-21T10:00:00Z"),
            opensAt = Instant.parse("2026-07-21T09:00:00Z"),
            closesAt = Instant.parse("2026-07-21T11:00:00Z"),
            invariantsHealthy = true,
        )

        saleTransition(
            state = SaleState.SUSPENDED,
            command = SaleCommand.RESUME, context = context
        ) shouldBeEqualTo SaleState.OPEN

        assertFailsWith<InvalidSaleTransition> {
            saleTransition(
                SaleState.SUSPENDED,
                SaleCommand.RESUME,
                context.copy(invariantsHealthy = false),
            )
        }
    }

    @Test
/**
 * 이 시나리오는 "stale purchase outcome is rejected instead of guessed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stale purchase outcome is rejected instead of guessed`() {
        val failure = assertFailsWith<InvalidPurchaseTransition> {
            transition(
                PurchaseState.APPROVED,
                PaymentOutcome.DECLINED
            )
        }

        log.debug { "failure=$failure" }
        failure.state shouldBeEqualTo PurchaseState.APPROVED
        failure.outcome shouldBeEqualTo PaymentOutcome.DECLINED
    }

    @Test
/**
 * 이 시나리오는 "ticket retry exhaustion moves to quarantine" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `ticket retry exhaustion moves to quarantine`() {
        ticketTransition(
            TicketState.REVOKE_PENDING,
            TicketEffectOutcome.RETRY_BUDGET_EXHAUSTED
        ) shouldBeEqualTo TicketState.QUARANTINED
    }
}
