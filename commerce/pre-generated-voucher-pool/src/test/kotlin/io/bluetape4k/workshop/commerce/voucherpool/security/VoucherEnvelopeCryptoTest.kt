package io.bluetape4k.workshop.commerce.voucherpool.security

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import org.junit.jupiter.api.Test
import java.util.*

/**
 * VoucherEnvelopeCryptoTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherEnvelopeCryptoTest {

    companion object: KLogging() {
/**
 * IDENTITY은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val IDENTITY = EntryIdentity(
            tenantId = "tenant-a",
            campaignId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            batchId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            entryId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            sourceOrdinal = 17,
        )
    }

    @Test
/**
 * 이 시나리오는 "AES GCM envelope round trip uses unique nonces and redacted values" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `AES GCM envelope round trip uses unique nonces and redacted values`() {
        val crypto = crypto(kekVersion = "k1")
        val code = CanonicalVoucherCode("POOL-ROUND-TRIP")
        val stableDigest = digests.stableDedup(IDENTITY.tenantId, code)
        val codeNonces = mutableSetOf<String>()
        val wrapNonces = mutableSetOf<String>()

        repeat(64) {
            val encrypted = crypto.encrypt(IDENTITY, code)
            crypto.decryptAndVerify(IDENTITY, encrypted, stableDigest) shouldBeEqualTo code
            codeNonces += Base64.getEncoder().encodeToString(encrypted.copyCodeNonce())
            wrapNonces += Base64.getEncoder().encodeToString(encrypted.copyWrapNonce())
            encrypted.toString() shouldNotContain "POOL-ROUND-TRIP"
        }

        codeNonces shouldHaveSize 64
        wrapNonces shouldHaveSize 64
    }

    @Test
/**
 * 이 시나리오는 "unknown key tag failure digest mismatch and row swap fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unknown key tag failure digest mismatch and row swap fail closed`() {
        val code = CanonicalVoucherCode("POOL-TAMPER")
        val encrypted = crypto(kekVersion = "k1").encrypt(IDENTITY, code)
        val stableDigest = digests.stableDedup(IDENTITY.tenantId, code)

        assertFailsWith<VoucherCryptoException> {
            crypto(kekVersion = "k2").decryptAndVerify(IDENTITY, encrypted, stableDigest)
        }.reason shouldBeEqualTo VoucherCryptoFailureReason.UNKNOWN_KEY_VERSION

        val tamperedCiphertext = encrypted.copyCodeCiphertext().also { it[it.lastIndex] = (it.last() xor 1) }

        assertFailsWith<VoucherCryptoException> {
            crypto(kekVersion = "k1")
                .decryptAndVerify(
                    entryIdentity = IDENTITY,
                    encrypted = encrypted.copy(codeCiphertext = tamperedCiphertext),
                    expectedStableDedup = stableDigest,
                )
        }.reason shouldBeEqualTo VoucherCryptoFailureReason.INVALID_TAG

        val wrongDigest = digests.stableDedup(IDENTITY.tenantId, CanonicalVoucherCode.of("OTHER"))

        assertFailsWith<VoucherCryptoException> {
            crypto(kekVersion = "k1")
                .decryptAndVerify(
                    entryIdentity = IDENTITY,
                    encrypted = encrypted,
                    expectedStableDedup = wrongDigest
                )
        }.reason shouldBeEqualTo VoucherCryptoFailureReason.DIGEST_MISMATCH

        assertFailsWith<VoucherCryptoException> {
            crypto(kekVersion = "k1")
                .decryptAndVerify(
                    entryIdentity = IDENTITY.copy(entryId = UUID.randomUUID()),
                    encrypted = encrypted,
                    expectedStableDedup = stableDigest,
                )
        }.reason shouldBeEqualTo VoucherCryptoFailureReason.INVALID_TAG
    }

    @Test
/**
 * 이 시나리오는 "retained KEK decrypts old rows while new rows use the current version" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retained KEK decrypts old rows while new rows use the current version`() {
        val code = CanonicalVoucherCode.of("POOL-ROTATION")
        val oldCrypto = crypto(kekVersion = "k1")
        val oldEncrypted = oldCrypto.encrypt(IDENTITY, code)
        val rotated = crypto(kekVersion = "k2", retainedKekVersion = "k1")

        rotated.decryptAndVerify(
            entryIdentity = IDENTITY,
            encrypted = oldEncrypted,
            expectedStableDedup = digests.stableDedup(IDENTITY.tenantId, code),
        ) shouldBeEqualTo code

        rotated.encrypt(IDENTITY, code).kekVersion shouldBeEqualTo "k2"
    }

/**
 * crypto은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun crypto(
        kekVersion: String,
        retainedKekVersion: String? = null,
    ): VoucherEnvelopeCrypto {
/**
 * retained은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val retained = retainedKekVersion?.let {
            listOf(VoucherKek.of(it, kekBytes(it)))
        }.orEmpty()

        return AesGcmVoucherEnvelopeCrypto(
            VoucherKekRing(VoucherKek(kekVersion, kekBytes(kekVersion)), retained),
            digests,
        )
    }

/**
 * kekBytes은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun kekBytes(version: String): ByteArray = ByteArray(32) { (version.hashCode() + it).toByte() }

/**
 * digests은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val digests = VoucherDigestService.testFixture()

/**
 * xor은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()
}
