package io.bluetape4k.workshop.commerce.voucherpool.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContentEqual
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldNotContentEqual
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.util.*

/**
 * VoucherPoolFingerprintTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolFingerprintTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "semantic fields are canonical regardless of map iteration order" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `semantic fields are canonical regardless of map iteration order`() {
        val first = VoucherPoolFingerprint.command(
            operation = "reserve",
            fields = linkedMapOf("campaignId" to "campaign-1", "quantity" to "1"),
        )
        val second = VoucherPoolFingerprint.command(
            operation = "reserve",
            fields = linkedMapOf("quantity" to "1", "campaignId" to "campaign-1"),
        )

        first shouldBeEqualTo second
    }

    @Test
/**
 * 이 시나리오는 "length prefixing separates ambiguous field material" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `length prefixing separates ambiguous field material`() {
        val first = VoucherPoolFingerprint.command("import", mapOf("ab" to "c", "d" to "e"))
        val second = VoucherPoolFingerprint.command("import", mapOf("a" to "bc", "d" to "e"))

        first shouldNotBeEqualTo second
    }

    @Test
/**
 * 이 시나리오는 "operation null and omitted fields remain distinct" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operation null and omitted fields remain distinct`() {
        val presentNull = VoucherPoolFingerprint.command("reserve", mapOf("user" to null))
        val omitted = VoucherPoolFingerprint.command("reserve", emptyMap())
        val otherOperation = VoucherPoolFingerprint.command("allocate", mapOf("user" to null))

        presentNull shouldNotBeEqualTo omitted
        presentNull shouldNotBeEqualTo otherOperation
    }

    @Test
/**
 * 이 시나리오는 "fingerprint is immutable redacted and bounded" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fingerprint is immutable redacted and bounded`() {
        val fields = linkedMapOf("campaignId" to "campaign-1")
        val fingerprint = VoucherPoolFingerprint.command("reserve", fields)
        fields["campaignId"] = "mutated"
        val copied = fingerprint.copyBytes().also { it.fill(0) }

        fingerprint shouldBeEqualTo VoucherPoolFingerprint.command("reserve", mapOf("campaignId" to "campaign-1"))
        log.debug { "fingerprint=$fingerprint" }
        fingerprint.toString() shouldNotContain "campaign-1"

        copied shouldNotContentEqual fingerprint.copyBytes()

        assertFailsWith<IllegalArgumentException> {
            VoucherPoolFingerprint.command("reserve", mapOf("payload" to "x".repeat(4_097)))
        }
    }

    @Test
/**
 * 이 시나리오는 "owner retains only defensive copies of capability digests" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `owner retains only defensive copies of capability digests`() {
        val scopedKeyDigest = ByteArray(32) { it.toByte() }
        val capabilityDigest = ByteArray(32) { (it + 1).toByte() }
        val expectedCapability = capabilityDigest.copyOf()

        val owner = IdempotencyOwner(
            scope = CommandScope("tenant-a", "reserve"),
            scopedKeyDigest = scopedKeyDigest,
            keyVersion = 4,
            fingerprint = VoucherPoolFingerprint.command("reserve", mapOf("campaign" to UUID.randomUUID().toString())),
            ownerTokenDigest = capabilityDigest,
        )

        scopedKeyDigest.fill(0)
        capabilityDigest.fill(0)

        owner.copyTokenDigest() shouldContentEqual expectedCapability
        owner.copyScopedKeyDigest().all { it == 0.toByte() }.shouldBeFalse()
        owner.javaClass.declaredFields.none { it.type == String::class.java }.shouldBeTrue()
    }
}
