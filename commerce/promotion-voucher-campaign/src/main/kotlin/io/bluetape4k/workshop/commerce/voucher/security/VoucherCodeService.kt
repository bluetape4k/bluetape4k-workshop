package io.bluetape4k.workshop.commerce.voucher.security

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.toUtf8Bytes
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * VoucherCodeKeyRing은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherCodeKeyRing(
    /**
     * `currentGenerationVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val currentGenerationVersion: Int,
    /**
     * `currentVerificationVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val currentVerificationVersion: Int,
    /**
     * `generationKeys`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generationKeys: Map<Int, ByteArray>,
    /**
     * `verificationKeys`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val verificationKeys: Map<Int, ByteArray>,
) {
    init {
        currentGenerationVersion.requireInRange(MIN_KEY_VERSION, MAX_KEY_VERSION, "currentGenerationVersion")
        currentVerificationVersion.requireInRange(MIN_KEY_VERSION, MAX_KEY_VERSION, "currentVerificationVersion")
        generationKeys[currentGenerationVersion].requireNotNull("current generation key")
        verificationKeys[currentVerificationVersion].requireNotNull("current verification key")
    }

    companion object {
        /**
         * `MIN_KEY_VERSION`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        private const val MIN_KEY_VERSION = 1
        /**
         * `MAX_KEY_VERSION`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        private const val MAX_KEY_VERSION = 99
    }
}

/**
 * VoucherGenerationInput은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherGenerationInput(
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
     * `allocationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val allocationId: UUID,
) {
    init {
        tenantId.requireNotBlank("tenantId")
    }

    /**
     * canonicalBytes은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun canonicalBytes(): ByteArray =
        "$tenantId\u0000$campaignId\u0000$allocationId".toUtf8Bytes()
}

/**
 * IssuedVoucherCode은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class IssuedVoucherCode(
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String,
    /**
     * `verifier`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val verifier: ByteArray,
    /**
     * `generationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generationKeyVersion: Int,
    /**
     * `verificationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val verificationKeyVersion: Int,
)

/**
 * VoucherLookupMaterial은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherLookupMaterial(
    /**
     * `verifier`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val verifier: ByteArray,
    /**
     * `verificationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val verificationKeyVersion: Int,
)

/**
 * VerificationResult은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class VerificationResult {
    /**
     * `VerificationResult.VALID`은 VerificationResult 업무 상태의 `VALID` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
     * 분기를 선택하는
     * 기준으로 사용합니다.
     */
    VALID,
    /**
     * `VerificationResult.INVALID_CODE`은 VerificationResult 업무 상태의 `INVALID_CODE` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    INVALID_CODE,
}

/** domain-separated verifier만 저장하면서 replay 가능한 opaque code를 생성합니다. */
/** domain-separated verifier만 저장하면서 replay 가능한 opaque code를 생성합니다. */
internal class VoucherCodeService(
    /**
     * `keyRing`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val keyRing: VoucherCodeKeyRing,
) {
    companion object: KLogging() {
        /**
         * `GENERATION_DOMAIN`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        private const val GENERATION_DOMAIN = "voucher-generation"
        /**
         * `VERIFIER_DOMAIN`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val VERIFIER_DOMAIN = "voucher-verifier"
        /**
         * `HMAC_SHA_256`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val HMAC_SHA_256 = "HmacSHA256"
        /**
         * `SHA_256`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val SHA_256 = "SHA-256"
        /**
         * `BASE58_ALPHABET`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        /**
         * `BASE58_ZERO`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val BASE58_ZERO = '1'
        /**
         * `TOKEN_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private const val TOKEN_BYTES = 16
        /**
         * `PAYLOAD_LENGTH`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        private const val PAYLOAD_LENGTH = 22
        /**
         * `CHECKSUM_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        private const val CHECKSUM_LENGTH = 2
        /**
         * `MIN_CODE_LENGTH`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        private const val MIN_CODE_LENGTH = 27
        /**
         * `MAX_CODE_LENGTH`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        private const val MAX_CODE_LENGTH = 28
        /**
         * `ASCII_RANGE`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val ASCII_RANGE = 0..127
        /**
         * `DOMAIN_SEPARATOR`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private val DOMAIN_SEPARATOR = byteArrayOf(0)
        /**
         * `DUMMY_KEY`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        private val DUMMY_KEY = ByteArray(32) { 0x5a }
        /**
         * `CODE_PATTERN`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        private val CODE_PATTERN =
            Regex("V([1-9][0-9]?)-([$BASE58_ALPHABET]{$PAYLOAD_LENGTH})([$BASE58_ALPHABET]{$CHECKSUM_LENGTH})")
    }

    /**
     * issue은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param input 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun issue(input: VoucherGenerationInput): IssuedVoucherCode =
        reconstruct(
            input,
            generationKeyVersion = keyRing.currentGenerationVersion,
            verificationKeyVersion = keyRing.currentVerificationVersion,
        ).checkNotNull { "current voucher keys disappeared after key-ring validation" }

    /** 기록된 key version 둘 다 사용할 수 있을 때만 committed code를 재구성합니다. */
    /** 기록된 key version 둘 다 사용할 수 있을 때만 committed code를 재구성합니다. */
    fun reconstruct(
        input: VoucherGenerationInput,
        generationKeyVersion: Int,
        verificationKeyVersion: Int,
    ): IssuedVoucherCode? {
        val generationKey = keyRing.generationKeys[generationKeyVersion] ?: return null
        val verificationKey = keyRing.verificationKeys[verificationKeyVersion] ?: return null
        val tokenMaterial = hmac(generationKey, GENERATION_DOMAIN, input.canonicalBytes()).copyOf(TOKEN_BYTES)
        val payload = Base58.encode(tokenMaterial).padStart(PAYLOAD_LENGTH, BASE58_ZERO)

        check(payload.length == PAYLOAD_LENGTH) {
            "voucher token material exceeded the bounded payload"
        }

        val prefix = "V$verificationKeyVersion-$payload"
        val code = prefix + checksum(prefix)
        val verifier = verifier(code, verificationKey)

        return IssuedVoucherCode(
            code = code,
            verifier = verifier,
            generationKeyVersion = generationKeyVersion,
            verificationKeyVersion = verificationKeyVersion,
        )
    }

    /** canonical external code를 opaque tenant-scoped lookup material로 변환합니다. */
    /** canonical external code를 opaque tenant-scoped lookup material로 변환합니다. */
    fun lookup(candidate: String): VoucherLookupMaterial? {
        val parsed = parse(candidate)
        val key = parsed?.let { keyRing.verificationKeys[it.verificationKeyVersion] } ?: DUMMY_KEY
        val digest = verifier(candidate.take(MAX_CODE_LENGTH), key)

        return if (parsed != null && keyRing.verificationKeys.containsKey(parsed.verificationKeyVersion)) {
            VoucherLookupMaterial(digest, parsed.verificationKeyVersion)
        } else {
            log.debug { "voucher_code_rejected reason=INVALID_CODE" }
            null
        }
    }

    /**
     * verify은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun verify(
        candidate: String,
        expectedVerifier: ByteArray,
        verificationKeyVersion: Int,
    ): Boolean {
        val parsed = parse(candidate)
        val key = keyRing.verificationKeys[verificationKeyVersion] ?: DUMMY_KEY
        val actualVerifier = verifier(candidate.take(MAX_CODE_LENGTH), key)
        val matched = MessageDigest.isEqual(actualVerifier, expectedVerifier)

        val valid = parsed != null &&
                parsed.verificationKeyVersion == verificationKeyVersion &&
                keyRing.verificationKeys.containsKey(verificationKeyVersion) &&
                matched

        if (!valid) {
            log.debug { "voucher_code_rejected reason=INVALID_CODE" }
        }
        return valid
    }

    /**
     * verifyExternal은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param candidate 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun verifyExternal(candidate: String): VerificationResult {
        val parsed = parse(candidate)
        val key = parsed?.let { keyRing.verificationKeys[it.verificationKeyVersion] } ?: DUMMY_KEY
        verifier(candidate.take(MAX_CODE_LENGTH), key)

        return if (parsed != null && keyRing.verificationKeys.containsKey(parsed.verificationKeyVersion)) {
            VerificationResult.VALID
        } else {
            log.debug { "voucher_code_rejected reason=INVALID_CODE" }
            VerificationResult.INVALID_CODE
        }
    }

    /**
     * parse은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param candidate 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun parse(candidate: String): ParsedVoucherCode? {
        if (candidate.length !in MIN_CODE_LENGTH..MAX_CODE_LENGTH || !candidate.all { it.code in ASCII_RANGE }) return null

        val match = CODE_PATTERN.matchEntire(candidate) ?: return null
        val verificationKeyVersion = match.groupValues[1].toIntOrNull() ?: return null
        val payload = match.groupValues[2]
        val suppliedChecksum = match.groupValues[3]
        val prefix = "V$verificationKeyVersion-$payload"

        if (!MessageDigest.isEqual(checksum(prefix).toUtf8Bytes(), suppliedChecksum.toUtf8Bytes())) return null
        return ParsedVoucherCode(verificationKeyVersion)
    }

    /**
     * verifier은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun verifier(
        code: String,
        key: ByteArray,
    ): ByteArray =
        hmac(key, VERIFIER_DOMAIN, code.toUtf8Bytes())

    /**
     * checksum은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param prefix 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun checksum(prefix: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(prefix.toUtf8Bytes())
        val checksumValue = (((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)) %
                    (BASE58_ALPHABET.length * BASE58_ALPHABET.length)

        return buildString(CHECKSUM_LENGTH) {
            append(BASE58_ALPHABET[checksumValue / BASE58_ALPHABET.length])
            append(BASE58_ALPHABET[checksumValue % BASE58_ALPHABET.length])
        }
    }

    /**
     * hmac은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun hmac(
        key: ByteArray,
        domain: String,
        input: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(key, HMAC_SHA_256))
        mac.update(domain.toUtf8Bytes())
        mac.update(DOMAIN_SEPARATOR)
        return mac.doFinal(input)
    }

    /**
     * ParsedVoucherCode은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private data class ParsedVoucherCode(val verificationKeyVersion: Int)

}
