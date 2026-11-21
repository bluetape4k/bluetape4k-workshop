@file:Suppress("TooManyFunctions") // Security value types and the transactional storage primitive share one boundary.

package io.bluetape4k.workshop.commerce.voucherpool.security

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.LockedVoucherCryptoRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.*
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * EntryIdentity은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class EntryIdentity(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID,
    /**
     * `entryId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val entryId: UUID,
    /**
     * `sourceOrdinal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sourceOrdinal: Long,
) {
    init {
        tenantId.requireNotBlank("tenantId")
        sourceOrdinal.requireGe(0, "sourceOrdinal")
    }
}

/**
 * VoucherKek은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherKek private constructor(
    /**
     * `version`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: String,
    /**
     * `material`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val material: ByteArray,
) {
    /**
     * copyMaterial은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    internal fun copyMaterial(): ByteArray = material.copyOf()

    /**
     * toString은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun toString(): String = "VoucherKek(version=$version, material=[REDACTED])"

    companion object {
        /**
         * invoke은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param version 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param material 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        operator fun invoke(version: String, material: ByteArray): VoucherKek {
            version.requireNotBlank("version")
            require(material.size in VALID_AES_KEY_BYTES) { "KEK must be a valid AES key" }

            return VoucherKek(version, material.copyOf())
        }

        /**
         * of은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param version 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param material 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun of(version: String, material: ByteArray): VoucherKek =
            invoke(version, material)
    }
}

/**
 * VoucherKekRing은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherKekRing private constructor(
    /**
     * `current`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val current: VoucherKek,
    retained: List<VoucherKek>,
) {
    /**
     * `readable`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val readable: Map<String, VoucherKek> =
        (listOf(current) + retained).associateBy(VoucherKek::version)

    init {
        require(readable.size == retained.size + 1) { "KEK versions must be unique" }
    }

    /**
     * require은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param version 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun require(version: String): VoucherKek =
        readable[version] ?: throw VoucherCryptoException(VoucherCryptoFailureReason.UNKNOWN_KEY_VERSION)

    companion object {
        /**
         * invoke은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param current 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param retained 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        operator fun invoke(current: VoucherKek, retained: List<VoucherKek> = emptyList()): VoucherKekRing =
            VoucherKekRing(current, retained.toList())

        /**
         * of은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param current 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param retained 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun of(current: VoucherKek, retained: List<VoucherKek> = emptyList()): VoucherKekRing =
            invoke(current, retained)
    }
}

/**
 * EncryptedVoucherCode은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class EncryptedVoucherCode private constructor(
    codeCiphertext: ByteArray,
    codeNonce: ByteArray,
    wrappedDek: ByteArray,
    wrapNonce: ByteArray,
    /**
     * `kekVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val kekVersion: String,
) {
    /**
     * `codeCiphertext`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val codeCiphertext = codeCiphertext.copyOf()
    /**
     * `codeNonce`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val codeNonce = codeNonce.copyOf()
    /**
     * `wrappedDek`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val wrappedDek = wrappedDek.copyOf()
    /**
     * `wrapNonce`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val wrapNonce = wrapNonce.copyOf()

    init {
        require(this.codeCiphertext.isNotEmpty()) { "code ciphertext must not be empty" }
        require(this.codeNonce.size == GCM_NONCE_BYTES) { "code nonce must be 96 bits" }
        require(this.wrappedDek.isNotEmpty()) { "wrapped DEK must not be empty" }
        require(this.wrapNonce.size == GCM_NONCE_BYTES) { "wrap nonce must be 96 bits" }
        require(kekVersion.isNotBlank()) { "KEK version must not be blank" }
    }

    /**
     * copyCodeCiphertext은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun copyCodeCiphertext(): ByteArray = codeCiphertext.copyOf()
    /**
     * copyCodeNonce은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun copyCodeNonce(): ByteArray = codeNonce.copyOf()
    /**
     * copyWrappedDek은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun copyWrappedDek(): ByteArray = wrappedDek.copyOf()
    /**
     * copyWrapNonce은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun copyWrapNonce(): ByteArray = wrapNonce.copyOf()

    /**
     * copy은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun copy(
        codeCiphertext: ByteArray = copyCodeCiphertext(),
        codeNonce: ByteArray = copyCodeNonce(),
        wrappedDek: ByteArray = copyWrappedDek(),
        wrapNonce: ByteArray = copyWrapNonce(),
        kekVersion: String = this.kekVersion,
    ): EncryptedVoucherCode = invoke(codeCiphertext, codeNonce, wrappedDek, wrapNonce, kekVersion)

    /**
     * toString은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun toString(): String = "EncryptedVoucherCode(kekVersion=$kekVersion, material=[REDACTED])"

    companion object {
        /**
         * invoke은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        operator fun invoke(
            codeCiphertext: ByteArray,
            codeNonce: ByteArray,
            wrappedDek: ByteArray,
            wrapNonce: ByteArray,
            kekVersion: String,
        ): EncryptedVoucherCode = EncryptedVoucherCode(codeCiphertext, codeNonce, wrappedDek, wrapNonce, kekVersion)

        /**
         * of은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        fun of(
            codeCiphertext: ByteArray,
            codeNonce: ByteArray,
            wrappedDek: ByteArray,
            wrapNonce: ByteArray,
            kekVersion: String,
        ): EncryptedVoucherCode = invoke(codeCiphertext, codeNonce, wrappedDek, wrapNonce, kekVersion)
    }
}

/**
 * VoucherCryptoFailureReason은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class VoucherCryptoFailureReason {
    /**
     * `VoucherCryptoFailureReason.UNKNOWN_KEY_VERSION`은 VoucherCryptoFailureReason 업무 상태의
     * `UNKNOWN_KEY_VERSION` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    UNKNOWN_KEY_VERSION,
    /**
     * `VoucherCryptoFailureReason.INVALID_CIPHERTEXT`은 VoucherCryptoFailureReason 업무 상태의
     * `INVALID_CIPHERTEXT` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    INVALID_CIPHERTEXT,
    /**
     * `VoucherCryptoFailureReason.INVALID_TAG`은 VoucherCryptoFailureReason 업무 상태의 `INVALID_TAG`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    INVALID_TAG,
    /**
     * `VoucherCryptoFailureReason.DIGEST_MISMATCH`은 VoucherCryptoFailureReason 업무 상태의
     * `DIGEST_MISMATCH`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    DIGEST_MISMATCH,
}

/**
 * VoucherCryptoException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherCryptoException(
    /**
     * `reason`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reason: VoucherCryptoFailureReason,
    cause: Throwable? = null,
): IllegalStateException(reason.name, cause)

/**
 * VoucherEnvelopeCrypto은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface VoucherEnvelopeCrypto {

    /**
     * encrypt은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param entryIdentity 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param code 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun encrypt(entryIdentity: EntryIdentity, code: CanonicalVoucherCode): EncryptedVoucherCode

    /**
     * decryptAndVerify은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun decryptAndVerify(
        entryIdentity: EntryIdentity,
        encrypted: EncryptedVoucherCode,
        expectedStableDedup: VoucherDigest,
    ): CanonicalVoucherCode
}

/** entry별 random DEK와 versioned KEK wrapping을 사용하는 JDK AES-GCM envelope encryption입니다. */
/** entry별 random DEK와 versioned KEK wrapping을 사용하는 JDK AES-GCM envelope encryption입니다. */
internal class AesGcmVoucherEnvelopeCrypto(
    /**
     * `kekRing`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val kekRing: VoucherKekRing,
    /**
     * `digests`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val digests: VoucherDigestService,
    /**
     * `secureRandom`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val secureRandom: SecureRandom = SecureRandom(),
): VoucherEnvelopeCrypto {

    companion object: KLogging()

    /**
     * encrypt은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param entryIdentity 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param code 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun encrypt(entryIdentity: EntryIdentity, code: CanonicalVoucherCode): EncryptedVoucherCode {
        val dek = randomBytes(DEK_BYTES)
        val codeNonce = randomBytes(GCM_NONCE_BYTES)
        val wrapNonce = randomBytes(GCM_NONCE_BYTES)
        val plaintext = code.withRawValue { it.toUtf8Bytes() }
        val kek = kekRing.current

        return try {
            EncryptedVoucherCode(
                codeCiphertext = encrypt(dek, codeNonce, codeAad(entryIdentity), plaintext),
                codeNonce = codeNonce,
                wrappedDek = withKeyMaterial(kek) { encrypt(it, wrapNonce, wrapAad(entryIdentity, kek.version), dek) },
                wrapNonce = wrapNonce,
                kekVersion = kek.version,
            )
        } catch (failure: GeneralSecurityException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_CIPHERTEXT, failure)
        } finally {
            dek.fill(0)
            plaintext.fill(0)
        }
    }

    @Suppress("ThrowsCount")
    /**
     * decryptAndVerify은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun decryptAndVerify(
        entryIdentity: EntryIdentity,
        encrypted: EncryptedVoucherCode,
        expectedStableDedup: VoucherDigest,
    ): CanonicalVoucherCode {
        val kek = kekRing.require(encrypted.kekVersion)

        val dek = try {
            withKeyMaterial(kek) {
                decrypt(
                    key = it,
                    nonce = encrypted.copyWrapNonce(),
                    aad = wrapAad(entryIdentity, kek.version),
                    ciphertext = encrypted.copyWrappedDek()
                )
            }
        } catch (failure: AEADBadTagException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_TAG, failure)
        } catch (failure: GeneralSecurityException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_CIPHERTEXT, failure)
        }

        val plaintext = try {
            decrypt(
                key = dek,
                nonce = encrypted.copyCodeNonce(),
                aad = codeAad(entryIdentity),
                ciphertext = encrypted.copyCodeCiphertext()
            )
        } catch (failure: AEADBadTagException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_TAG, failure)
        } catch (failure: GeneralSecurityException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_CIPHERTEXT, failure)
        } finally {
            dek.fill(0)
        }

        return try {
            val code = CanonicalVoucherCode(decodeUtf8(plaintext))
            val matches = try {
                digests.matchesStableDedup(entryIdentity.tenantId, code, expectedStableDedup)
            } catch (failure: VoucherKeyMaterialUnavailableException) {
                throw VoucherCryptoException(VoucherCryptoFailureReason.UNKNOWN_KEY_VERSION, failure)
            }
            if (!matches) throw VoucherCryptoException(VoucherCryptoFailureReason.DIGEST_MISMATCH)
            code
        } catch (failure: VoucherCryptoException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_CIPHERTEXT, failure)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * randomBytes은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param size 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also(secureRandom::nextBytes)

    /**
     * codeAad은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param identity 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun codeAad(identity: EntryIdentity): ByteArray =
        CanonicalFields.encode(
            "voucher-code-aad-v1",
            identity.tenantId.toUtf8Bytes(),
            identity.campaignId.bytes(),
            identity.batchId.bytes(),
            identity.entryId.bytes(),
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(identity.sourceOrdinal).array(),
        )

    /**
     * wrapAad은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param identity 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param kekVersion 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun wrapAad(identity: EntryIdentity, kekVersion: String): ByteArray =
        CanonicalFields.encode(
            "voucher-dek-wrap-aad-v1",
            identity.tenantId.toUtf8Bytes(),
            identity.campaignId.bytes(),
            identity.batchId.bytes(),
            identity.entryId.bytes(),
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(identity.sourceOrdinal).array(),
            kekVersion.toUtf8Bytes(),
        )

    /**
     * encrypt은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param key 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param nonce 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param aad 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param plaintext 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    /**
     * decrypt은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param key 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param nonce 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param aad 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param ciphertext 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)

    /**
     * cipher은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param mode 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param key 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param nonce 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param aad 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance(AES_GCM)
            .apply {
                init(mode, SecretKeySpec(key, AES), GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(aad)
            }

/**
 * withKeyMaterial은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private inline fun <T> withKeyMaterial(key: VoucherKek, block: (ByteArray) -> T): T {
        val material = key.copyMaterial()

        return try {
            block(material)
        } finally {
            material.fill(0)
        }
    }

    /**
     * decodeUtf8은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param bytes 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

/** caller-owned reveal transaction 안에서 compose되는 entry-lock crypto primitive입니다. */
/** caller-owned reveal transaction 안에서 compose되는 entry-lock crypto primitive입니다. */
internal class VoucherCryptoStorage(
    /**
     * `repository`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: VoucherPoolRepository,
    /**
     * `crypto`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val crypto: VoucherEnvelopeCrypto,
) {
    /** reveal ciphertext를 소비하지 않고 allocation digesting을 위해 caller-locked RESERVED entry를 decrypt합니다. */
    /** reveal ciphertext를 소비하지 않고 allocation digesting을 위해 caller-locked RESERVED entry를 decrypt합니다. */
    @Suppress("SwallowedException")
    fun decryptRetained(
        identity: EntryIdentity,
        expectedRevision: Long,
    ): VoucherCryptoStorageOutcome {
        val locked: LockedVoucherCryptoRecord = repository.lockReservedCryptoEntry(
            identity.tenantId,
            identity.campaignId,
            identity.batchId,
            identity.entryId,
            identity.sourceOrdinal,
            expectedRevision,
        ) ?: error("reserved voucher entry is missing, stale, or quarantined")

        return try {
            VoucherCryptoStorageOutcome.Revealed(
                crypto.decryptAndVerify(identity, locked.encrypted(), locked.stableDedup()),
            )
        } catch (failure: VoucherCryptoException) {
            quarantine(identity, locked, failure.reason)
            VoucherCryptoStorageOutcome.Quarantined(failure.reason)
        } catch (failure: IllegalArgumentException) {
            quarantine(identity, locked, VoucherCryptoFailureReason.INVALID_CIPHERTEXT)
            VoucherCryptoStorageOutcome.Quarantined(VoucherCryptoFailureReason.INVALID_CIPHERTEXT)
        }
    }

    // bounded quarantine reason만 저장하고 malformed crypto material은 절대 노출하지 않습니다.
    @Suppress("SwallowedException")
    /**
     * decryptAndErase은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun decryptAndErase(
        identity: EntryIdentity,
        expectedRevision: Long,
    ): VoucherCryptoStorageOutcome {
        val locked: LockedVoucherCryptoRecord = repository.lockAllocatedCryptoEntry(
            identity.tenantId,
            identity.campaignId,
            identity.batchId,
            identity.entryId,
            identity.sourceOrdinal,
            expectedRevision,
        ) ?: error("encrypted voucher entry is missing, stale, revealed, or already quarantined")

        return try {
            val code = crypto.decryptAndVerify(
                entryIdentity = identity,
                encrypted = locked.encrypted(),
                expectedStableDedup = locked.stableDedup()
            )
            repository.eraseVoucherCiphertext(identity.tenantId, identity.entryId, expectedRevision)
            VoucherCryptoStorageOutcome.Revealed(code)
        } catch (failure: VoucherCryptoException) {
            quarantine(identity, locked, failure.reason)
            VoucherCryptoStorageOutcome.Quarantined(failure.reason)
        } catch (failure: IllegalArgumentException) {
            quarantine(identity, locked, VoucherCryptoFailureReason.INVALID_CIPHERTEXT)
            VoucherCryptoStorageOutcome.Quarantined(VoucherCryptoFailureReason.INVALID_CIPHERTEXT)
        }
    }

    /**
     * quarantine은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun quarantine(
        identity: EntryIdentity,
        locked: LockedVoucherCryptoRecord,
        reason: VoucherCryptoFailureReason,
    ) =
        repository.quarantineVoucherCrypto(
            tenantId = identity.tenantId,
            entryId = identity.entryId,
            sourceState = locked.state,
            sourceRevision = locked.revision,
            reasonCode = reason.name,
        )

/**
 * stableDedup은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun LockedVoucherCryptoRecord.stableDedup(): VoucherDigest =
        VoucherDigest(
            purpose = DigestPurpose.STABLE_DEDUP,
            keyVersion = stableDedupKeyVersion,
            value = stableDedupDigest.requireCryptoBytes(),
        )

/**
 * encrypted은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun LockedVoucherCryptoRecord.encrypted(): EncryptedVoucherCode =
        EncryptedVoucherCode(
            codeCiphertext = codeCiphertext.requireCryptoBytes(),
            codeNonce = codeNonce.requireCryptoBytes(),
            wrappedDek = wrappedDek.requireCryptoBytes(),
            wrapNonce = wrapNonce.requireCryptoBytes(),
            kekVersion = kekVersion ?: throw IllegalArgumentException("missing KEK version"),
        )
}

/**
 * VoucherCryptoStorageOutcome은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal sealed interface VoucherCryptoStorageOutcome {
    /**
     * Revealed은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Revealed(val code: CanonicalVoucherCode): VoucherCryptoStorageOutcome
    /**
     * Quarantined은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Quarantined(val reason: VoucherCryptoFailureReason): VoucherCryptoStorageOutcome
}

private fun DigestValue?.requireCryptoBytes(): ByteArray =
    this?.copyBytes() ?: throw IllegalArgumentException("missing crypto material")

/**
 * bytes은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun UUID.bytes(): ByteArray =
    ByteBuffer
        .allocate(Long.SIZE_BYTES * 2)
        .putLong(mostSignificantBits)
        .putLong(leastSignificantBits)
        .array()

/**
 * `AES`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val AES = "AES"
/**
 * `AES_GCM`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val AES_GCM = "AES/GCM/NoPadding"
/**
 * `GCM_TAG_BITS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val GCM_TAG_BITS = 128
/**
 * `GCM_NONCE_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val GCM_NONCE_BYTES = 12
/**
 * `DEK_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val DEK_BYTES = 32
/**
 * `AES_128_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val AES_128_BYTES = 16
/**
 * `AES_192_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val AES_192_BYTES = 24
/**
 * `AES_256_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val AES_256_BYTES = 32
/**
 * `VALID_AES_KEY_BYTES`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
 *
 * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
 */
private val VALID_AES_KEY_BYTES = setOf(AES_128_BYTES, AES_192_BYTES, AES_256_BYTES)
