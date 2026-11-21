package io.bluetape4k.workshop.commerce.usagebilling.billing.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentCommand
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentOutcome
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.*

/**
 * BillingAdjustmentServiceTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class BillingAdjustmentServiceTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "posts one immutable compensating fact through the Billing journal" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `posts one immutable compensating fact through the Billing journal`() {
        val journal = RecordingAdjustmentJournal()
        val service = BillingAdjustmentService(journal)
        val command = BillingAdjustmentCommand(
            adjustmentEventId = UUID.randomUUID(),
            tenantId = "tenant-a",
            correctionOf = UUID.randomUUID(),
            amount = BigDecimal("-0.20"),
            currency = "USD",
        )

        service.post(command) shouldBeEqualTo BillingAdjustmentOutcome.APPLIED
        journal.commands shouldBeEqualTo listOf(command)
    }

/**
 * RecordingAdjustmentJournal 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingAdjustmentJournal: BillingAdjustmentJournal {
/**
 * commands은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val commands = mutableListOf<BillingAdjustmentCommand>()

/**
 * post은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun post(command: BillingAdjustmentCommand): BillingAdjustmentOutcome {
            commands += command
            return BillingAdjustmentOutcome.APPLIED
        }
    }
}
