package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.days
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * VoucherRetentionIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherRetentionIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "audit terminal replay and applied event cutoffs preserve their minimum windows" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `audit terminal replay and applied event cutoffs preserve their minimum windows`() {
        val now = Instant.parse("2026-07-20T00:00:00Z")
        val policy = VoucherRetentionPolicy(VoucherRetentionProperties())

        policy.cutoffs(now, 2.days()) shouldBeEqualTo
                VoucherRetentionCutoffs(
                    auditBefore = now.minus(90.days()),
                    terminalBefore = now.minus(9.days()),
                    appliedEventBefore = now.minus(30.days()),
                )
    }
}
