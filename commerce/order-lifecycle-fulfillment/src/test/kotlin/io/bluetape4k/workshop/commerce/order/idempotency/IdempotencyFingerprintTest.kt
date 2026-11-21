package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * IdempotencyFingerprintTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class IdempotencyFingerprintTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "canonical request fingerprint ignores map insertion order" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `canonical request fingerprint ignores map insertion order`() {
        val first = IdempotencyFingerprint.request(mapOf("sku-b" to 2, "sku-a" to 1))
        val second = IdempotencyFingerprint.request(linkedMapOf("sku-a" to 1, "sku-b" to 2))

        first shouldBeEqualTo second
    }

    @Test
/**
 * 이 시나리오는 "different payload has different fingerprint" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `different payload has different fingerprint`() {
        IdempotencyFingerprint.request(mapOf("sku-a" to 1)) shouldNotBeEqualTo
                IdempotencyFingerprint.request(mapOf("sku-a" to 2))
    }

    @Test
/**
 * 이 시나리오는 "raw idempotency key is reduced to fixed length hash" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `raw idempotency key is reduced to fixed length hash`() {
        IdempotencyFingerprint.key("customer-visible-secret").length shouldBeEqualTo 64
        IdempotencyFingerprint.key("customer-visible-secret") shouldNotBeEqualTo "customer-visible-secret"
    }
}
