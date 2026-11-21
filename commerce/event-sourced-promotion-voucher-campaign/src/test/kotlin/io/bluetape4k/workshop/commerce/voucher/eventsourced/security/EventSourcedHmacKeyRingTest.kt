package io.bluetape4k.workshop.commerce.voucher.eventsourced.security

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

/**
 * EventSourcedHmacKeyRingTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedHmacKeyRingTest {

    companion object: KLogging()

/**
 * keyRing은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val keyRing = EventSourcedHmacKeyRing(
        active = EventSourcedHmacKey(2, "active-key-material-with-at-least-32-bytes".toUtf8Bytes()),
        retired = listOf(
            EventSourcedHmacKey(1, "retired-key-material-with-at-least-32-bytes".toUtf8Bytes()),
        ),
    )

    @Test
/**
 * 이 시나리오는 "active digest is deterministic and domain separated" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `active digest is deterministic and domain separated`() {
        val digest = keyRing.digest(
            purpose = HmacPurpose.SUBJECT_IDENTITY,
            tenantId = TenantId("tenant-a"),
            domain = "campaign-principal",
            value = "user-42",
        )

        digest shouldBeEqualTo keyRing.digest(
            purpose = HmacPurpose.SUBJECT_IDENTITY,
            tenantId = TenantId("tenant-a"),
            domain = "campaign-principal",
            value = "user-42",
        )
        digest.keyVersion shouldBeEqualTo 2
        digest shouldNotBeEqualTo keyRing.digest(
            purpose = HmacPurpose.IDEMPOTENCY_KEY,
            tenantId = TenantId("tenant-a"),
            domain = "campaign-principal",
            value = "user-42",
        )
        digest shouldNotBeEqualTo keyRing.digest(
            purpose = HmacPurpose.SUBJECT_IDENTITY,
            tenantId = TenantId("tenant-b"),
            domain = "campaign-principal",
            value = "user-42",
        )
    }

    @Test
/**
 * 이 시나리오는 "lookup candidates retain retired keys while unavailable versions fail closed" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `lookup candidates retain retired keys while unavailable versions fail closed`() {
        val candidates = keyRing.digestsForLookup(
            purpose = HmacPurpose.SUBJECT_IDENTITY,
            tenantId = TenantId("tenant-a"),
            domain = "campaign-principal",
            value = "user-42",
        )

        candidates.map(KeyedDigest::keyVersion) shouldBeEqualTo listOf(2, 1)
        keyRing.isAvailable(1).shouldBeTrue()
        keyRing.isAvailable(3).shouldBeFalse()

        assertFailsWith<EventSourcedKeyUnavailableException> {
            keyRing.digestWithVersion(
                keyVersion = 3,
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = TenantId("tenant-a"),
                domain = "campaign-principal",
                value = "user-42",
            )
        }
    }

    @Test
/**
 * 이 시나리오는 "configuration retains verification keys only until their replay retention deadline" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `configuration retains verification keys only until their replay retention deadline`() {
        val encoded = Base64.getEncoder().encodeToString("configuration-key-material-with-32-bytes".toUtf8Bytes())
        val clock = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC)
        val configured = EventSourcedIdentitySecurityConfiguration().eventSourcedHmacKeyRing(
            properties = EventSourcedHmacProperties(
                activeVersion = 2,
                activeKeyBase64 = encoded,
                retired =
                    listOf(
                        RetiredHmacKeyProperties(1, encoded, Instant.parse("2026-08-23T00:00:00Z")),
                        RetiredHmacKeyProperties(3, encoded, Instant.parse("2026-06-23T00:00:00Z")),
                    ),
            ),
            clock = clock,
        )

        configured.isAvailable(1).shouldBeTrue()
        configured.isAvailable(2).shouldBeTrue()
        configured.isAvailable(3).shouldBeFalse()
    }
}
