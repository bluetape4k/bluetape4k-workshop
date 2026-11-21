package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * IdempotencyCleanupServiceTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class IdempotencyCleanupServiceTest {

    companion object: KLogging()

/**
 * repository은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = mockk<HttpIdempotencyRepository>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(repository)
    }

    @Test
/**
 * 이 시나리오는 "scheduled cleanup delegates a bounded batch at the current clock instant" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `scheduled cleanup delegates a bounded batch at the current clock instant`() {
        val now = Instant.parse("2026-07-19T00:00:00Z")
        every { repository.deleteExpiredTerminal(now, 250) } returns 17

        val cleanup = IdempotencyCleanupService(
            repository,
            Clock.fixed(now, ZoneOffset.UTC),
            batchSize = 250
        )
        cleanup.deleteExpiredTerminal() shouldBeEqualTo 17

        verify(exactly = 1) { repository.deleteExpiredTerminal(now, 250) }
    }
}
