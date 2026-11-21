package io.bluetape4k.workshop.commerce.voucher.eventsourced.security

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import java.io.Serializable
import java.nio.charset.StandardCharsets.UTF_8
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `HMAC_SHA_256`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val HMAC_SHA_256 = "HmacSHA256"
/**
 * `MINIMUM_HMAC_KEY_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val MINIMUM_HMAC_KEY_BYTES = 32
/**
 * `SHA_256_HEX_LENGTH`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val SHA_256_HEX_LENGTH = 64
/**
 * `HMAC_CONTEXT_VERSION`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
private const val HMAC_CONTEXT_VERSION = "voucher-hmac-v1"

/**
 * `HmacPurpose`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 * @property value 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal enum class HmacPurpose(
    /**
     * `value`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val value: String,
) {
    /**
     * `HmacPurpose.SUBJECT_IDENTITY`은 `subject identity` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한
     * 뒤 사용합니다.
     */
    SUBJECT_IDENTITY("subject-identity"),
    /**
     * `HmacPurpose.PRINCIPAL_SCOPE`은 `principal scope` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한 뒤
     * 사용합니다.
     */
    PRINCIPAL_SCOPE("principal-scope"),
    /**
     * `HmacPurpose.IDEMPOTENCY_KEY`은 `idempotency key` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한 뒤
     * 사용합니다.
     */
    IDEMPOTENCY_KEY("idempotency-key"),
    /**
     * `HmacPurpose.OPERATOR_ACTOR`은 `operator actor` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤
     * 사용합니다.
     */
    OPERATOR_ACTOR("operator-actor"),
    /**
     * `HmacPurpose.OPERATOR_REQUEST`은 `operator request` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한
     * 뒤 사용합니다.
     */
    OPERATOR_REQUEST("operator-request"),
    /**
     * `HmacPurpose.VOUCHER_CODE`은 `voucher code` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한
     * 뒤 사용합니다.
     */
    VOUCHER_CODE("voucher-code"),
}

/**
 * versioned HMAC key 하나입니다. key material은 ingress에서 복사되며 절대 노출하지 않습니다.
 *
 * bluetape4k의 Tink helper는 내부 생성 key를 소유하고 externally versioned key material로 계산할 수 없으므로,
 * 여기서 JCA를 의도적으로 감쌉니다.
 */
internal class EventSourcedHmacKey(
    version: Int,
    material: ByteArray,
) {
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val version: Int = version.requirePositiveNumber("hmacKey.version")
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    private val secret = material.copyOf().also { it.size.requireGe(MINIMUM_HMAC_KEY_BYTES, "hmacKey.bytes") }

    /**
     * compute은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun compute(value: ByteArray): ByteArray =
        Mac
            .getInstance(HMAC_SHA_256)
            .apply { init(SecretKeySpec(secret, HMAC_SHA_256)) }
            .doFinal(value)
}

@ConsistentCopyVisibility
/**
 * KeyedDigest은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class KeyedDigest private constructor(
    /**
     * `value`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val value: String,
    /**
     * `keyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val keyVersion: Int,
) : Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param keyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            value: String,
            keyVersion: Int,
        ): KeyedDigest =
            KeyedDigest(
                value = value.requireNotBlank("digest"),
                keyVersion = keyVersion.requirePositiveNumber("digest.keyVersion"),
            )
    }

    init {
        value.length.requireEquals(SHA_256_HEX_LENGTH, "digest.length")
        value.all { it in '0'..'9' || it in 'a'..'f' }.requireEquals(true, "digest.lowercaseHex")
    }
}
/**
 * EventSourcedKeyUnavailableException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property keyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 */


internal class EventSourcedKeyUnavailableException(
    /**
     * `keyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val keyVersion: Int,
) : IllegalStateException("HMAC key version $keyVersion is unavailable")

/**
 * 안정적인 domain-separated correlation에 사용하는 active key와 retention-bound retired key입니다.
 * @property active 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class EventSourcedHmacKeyRing(
    /**
     * `active`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val active: EventSourcedHmacKey,
    retired: List<EventSourcedHmacKey> = emptyList(),
) {
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    private val orderedKeys = listOf(active) + retired
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    private val keysByVersion = orderedKeys.associateBy(EventSourcedHmacKey::version)

    init {
        keysByVersion.size.requireEquals(orderedKeys.size, "uniqueHmacKeyVersions.size")
    }

    /**
     * digest은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param purpose 업무 처리에 필요한 purpose 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun digest(
        purpose: HmacPurpose,
        tenantId: TenantId,
        domain: String,
        value: String,
    ): KeyedDigest =
        digest(
            key = active,
            purpose = purpose,
            tenantId = tenantId,
            domain = domain,
            value = value,
        )

    /**
     * digestWithVersion은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param keyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param purpose 업무 처리에 필요한 purpose 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun digestWithVersion(
        keyVersion: Int,
        purpose: HmacPurpose,
        tenantId: TenantId,
        domain: String,
        value: String,
    ): KeyedDigest =
        digest(
            key = keysByVersion[keyVersion.requirePositiveNumber("keyVersion")]
                    ?: throw EventSourcedKeyUnavailableException(keyVersion),
            purpose = purpose,
            tenantId = tenantId,
            domain = domain,
            value = value,
        )

    /**
     * digestsForLookup은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param purpose 업무 처리에 필요한 purpose 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun digestsForLookup(
        purpose: HmacPurpose,
        tenantId: TenantId,
        domain: String,
        value: String,
    ): List<KeyedDigest> =
        orderedKeys.map { key ->
            digest(
                key = key,
                purpose = purpose,
                tenantId = tenantId,
                domain = domain,
                value = value,
            )
        }

    /**
     * isAvailable은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param keyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun isAvailable(keyVersion: Int): Boolean = keyVersion in keysByVersion

    /**
     * digest은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param purpose 업무 처리에 필요한 purpose 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun digest(
        key: EventSourcedHmacKey,
        purpose: HmacPurpose,
        tenantId: TenantId,
        domain: String,
        value: String,
    ): KeyedDigest {
        val validDomain = domain.requireNotBlank("hmac.domain")
        val validValue = value.requireNotBlank("hmac.value")
        val message = listOf(
                HMAC_CONTEXT_VERSION,
                purpose.value,
                key.version,
                tenantId.value,
                validDomain,
                validValue,
            ).joinToString("\u0000")

        return KeyedDigest(
            value = HexFormat.of().formatHex(key.compute(message.toUtf8Bytes())),
            keyVersion = key.version,
        )
    }
}
