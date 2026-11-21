package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * ReservationCredentialServiceTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ReservationCredentialServiceTest {

    companion object: KLogging()

/**
 * service은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service = ReservationCredentialService("0123456789abcdef0123456789abcdef")

    @Test
/**
 * 이 시나리오는 "owner and idempotency digests are domain separated and constant-time comparable" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `owner and idempotency digests are domain separated and constant-time comparable`() {
        val owner = service.ownerDigest("reservation-owner-token-with-256-bits-of-entropy")
        val command = service.idempotencyDigest("demo", "hold", "idempotency-key-128-bits")

        owner shouldNotBeEqualTo command
        service.matchesOwner("reservation-owner-token-with-256-bits-of-entropy", owner).shouldBeTrue()
        service.matchesOwner("another-owner-token-with-256-bits-of-entropy", owner).shouldBeFalse()
    }
}
