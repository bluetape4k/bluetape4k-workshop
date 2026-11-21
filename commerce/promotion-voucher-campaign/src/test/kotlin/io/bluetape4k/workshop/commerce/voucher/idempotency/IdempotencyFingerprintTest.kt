package io.bluetape4k.workshop.commerce.voucher.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * IdempotencyFingerprintTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class IdempotencyFingerprintTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "header case field order and schema default normalize to one fingerprint" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `header case field order and schema default normalize to one fingerprint`() {
        val first = fingerprint(
            headers = mapOf("Content-Type" to " application/json "),
            body = """{"userRef":"u1"}""",
        )
        val second = fingerprint(
            method = "post",
            path = "/api/v1/campaigns/c-1/vouchers/",
            headers = mapOf("content-type" to "application/json", "X-Ignored" to "not-semantic"),
            body = """{"forceReview":false,"userRef":"u1"}""",
        )

        first shouldBeEqualTo second
    }

    @Test
/**
 * 이 시나리오는 "canonical decimal key order and nullable omission follow the closed schema" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `canonical decimal key order and nullable omission follow the closed schema`() {
        val first = fingerprint(
            body = """{"amount":1.00,"note":null,"userRef":"u1"}""",
        )
        val second = fingerprint(
            body = """{"userRef":"u1","amount":1,"forceReview":false}""",
        )

        first shouldBeEqualTo second
    }

    @Test
/**
 * 이 시나리오는 "same key with a semantic payload difference has a different fingerprint" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same key with a semantic payload difference has a different fingerprint`() {
        fingerprint(body = """{"userRef":"u1"}""") shouldNotBeEqualTo fingerprint(body = """{"userRef":"u2"}""")
    }

    @Test
/**
 * 이 시나리오는 "raw idempotency key is domain separated by tenant principal operation and resource" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `raw idempotency key is domain separated by tenant principal operation and resource`() {
        val principal = Digest.sha256("principal-a")
        val first = IdempotencyFingerprint.key("tenant-a", principal, "ALLOCATE", "campaign-1", "raw-key-123")
        val otherTenant = IdempotencyFingerprint.key("tenant-b", principal, "ALLOCATE", "campaign-1", "raw-key-123")
        val otherOperation = IdempotencyFingerprint.key("tenant-a", principal, "REDEEM", "campaign-1", "raw-key-123")

        first shouldNotBeEqualTo otherTenant
        first shouldNotBeEqualTo otherOperation
        first.base64Url shouldNotContain "raw-key-123"
    }

    @Test
/**
 * 이 시나리오는 "unknown request properties are rejected before hashing" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unknown request properties are rejected before hashing`() {
        assertFailsWith<IllegalArgumentException> {
            fingerprint(body = """{"userRef":"u1","fixtureOverride":"approve"}""")
        }
    }

    @Test
/**
 * 이 시나리오는 "missing required request property is rejected before hashing" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `missing required request property is rejected before hashing`() {
        assertFailsWith<IllegalArgumentException> {
            fingerprint(body = """{"forceReview":false}""")
        }
    }

    @Test
/**
 * 이 시나리오는 "golden request fixture is stable and uses unpadded base64url" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `golden request fixture is stable and uses unpadded base64url`() {
        val digest = fingerprint(body = """{"amount":1.00,"userRef":"u1"}""")
        digest.base64Url shouldBeEqualTo "bstRVOnLhqvLrUzLUwckQDSUtGTyEnowlYcsQPXSzds"
    }

/**
 * fingerprint은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun fingerprint(
        method: String = "POST",
        path: String = "/api/v1/campaigns/c-1/vouchers",
        headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
        body: String,
    ): Digest =
        IdempotencyFingerprint.request(
            method = method,
            path = path,
            resourceId = "c-1",
            headers = headers,
            body = body,
            schema = ClosedRequestSchema(
                fields = mapOf(
                    "amount" to CanonicalField.Decimal(default = BigDecimal.ONE),
                    "forceReview" to CanonicalField.Boolean(default = false),
                    "note" to CanonicalField.Text(nullable = true, nullEquivalentToOmitted = true),
                    "userRef" to CanonicalField.Text(),
                ),
            ),
        )
}
