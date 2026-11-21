package io.bluetape4k.workshop.commerce.voucherpool.security

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.hashOf
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * DigestPurpose은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class DigestPurpose {
    /**
     * `DigestPurpose.STABLE_DEDUP`은 DigestPurpose 업무 상태의 `STABLE_DEDUP` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    STABLE_DEDUP,
    /**
     * `DigestPurpose.VERIFICATION`은 DigestPurpose 업무 상태의 `VERIFICATION` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    VERIFICATION,
    /**
     * `DigestPurpose.USER_IDENTITY`은 DigestPurpose 업무 상태의 `USER_IDENTITY` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    USER_IDENTITY,
    /**
     * `DigestPurpose.COMMAND_TOMBSTONE`은 DigestPurpose 업무 상태의 `COMMAND_TOMBSTONE` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    COMMAND_TOMBSTONE,
    /**
     * `DigestPurpose.REDIS_SIGNAL`은 DigestPurpose 업무 상태의 `REDIS_SIGNAL` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    REDIS_SIGNAL,
    /**
     * `DigestPurpose.AUDIT`은 DigestPurpose 업무 상태의 `AUDIT` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    AUDIT,
}

/**
 * DigestKey은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class DigestKey private constructor(
    /**
     * `version`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: Int,
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
    override fun toString(): String = "DigestKey(version=$version, material=[REDACTED])"

    companion object {
        /**
         * `MINIMUM_KEY_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private const val MINIMUM_KEY_BYTES = 32

        /**
         * invoke은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param version 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param material 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        operator fun invoke(version: Int, material: ByteArray): DigestKey {
            version.requirePositiveNumber("version")
            material.size.requireGe(MINIMUM_KEY_BYTES) { "digest key must contain at least 256 bits" }
            return DigestKey(version, material.copyOf())
        }

        /**
         * of은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param version 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param material 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun of(version: Int, material: ByteArray): DigestKey = invoke(version, material)
    }
}

/**
 * DigestKeyRing은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class DigestKeyRing private constructor(
    /**
     * `current`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val current: DigestKey,
    retained: List<DigestKey>,
) {
    /**
     * `readable`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val readable: Map<Int, DigestKey> =
        (listOf(current) + retained).associateBy(DigestKey::version)

    init {
        require(readable.size == retained.size + 1) { "digest key versions must be unique" }
    }

    /**
     * require은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param version 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun require(version: Int): DigestKey =
        readable[version] ?: throw VoucherKeyMaterialUnavailableException()

    companion object {
        /**
         * invoke은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param current 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param retained 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        operator fun invoke(current: DigestKey, retained: List<DigestKey> = emptyList()): DigestKeyRing =
            DigestKeyRing(current, retained.toList())

        /**
         * of은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param current 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param retained 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun of(current: DigestKey, retained: List<DigestKey> = emptyList()): DigestKeyRing =
            invoke(current, retained)
    }
}

/**
 * VoucherDigest은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherDigest private constructor(
    /**
     * `purpose`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val purpose: DigestPurpose,
    /**
     * `keyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val keyVersion: Int,
    /**
     * `value`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val value: ByteArray,
) {
    /**
     * copyBytes은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun copyBytes(): ByteArray = value.copyOf()

    /**
     * equals은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param other 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun equals(other: Any?): Boolean =
        other is VoucherDigest &&
                purpose == other.purpose &&
                keyVersion == other.keyVersion &&
                value.contentEquals(other.value)

    /**
     * hashCode은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun hashCode(): Int = hashOf(purpose, keyVersion, value, value.contentHashCode())

    /**
     * toString은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun toString(): String = "VoucherDigest(purpose=$purpose, keyVersion=$keyVersion, value=[REDACTED])"

    companion object {
        /**
         * invoke은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param purpose 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param keyVersion 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        operator fun invoke(purpose: DigestPurpose, keyVersion: Int, value: ByteArray): VoucherDigest {
            keyVersion.requirePositiveNumber("version")
            require(value.isNotEmpty()) { "digest value must not be empty" }

            return VoucherDigest(purpose, keyVersion, value.copyOf())
        }

        /**
         * of은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param purpose 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param keyVersion 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun of(purpose: DigestPurpose, keyVersion: Int, value: ByteArray): VoucherDigest =
            invoke(purpose, keyVersion, value)
    }
}

/**
 * VoucherKeyMaterialUnavailableException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherKeyMaterialUnavailableException:
    IllegalStateException("required voucher key material is unavailable")

/** canonical length-prefixed field 위에서 purpose-separated, versioned HMAC-SHA256 digest를 계산합니다. */
/** canonical length-prefixed field 위에서 purpose-separated, versioned HMAC-SHA256 digest를 계산합니다. */
@Suppress("TooManyFunctions") // One cohesive facade keeps every digest purpose behind the same key boundary.
internal class VoucherDigestService(
    /**
     * `stableDedupKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val stableDedupKey: DigestKey,
    /**
     * `commandTombstoneKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val commandTombstoneKey: DigestKey,
    rotatingKeys: Map<DigestPurpose, DigestKeyRing>,
) {
    /**
     * `rotatingKeys`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val rotatingKeys = rotatingKeys.toMap()

    /** 고정 tenant-lifetime command digest authority version입니다. key material은 encapsulated 상태로 유지됩니다. */
    /** 고정 tenant-lifetime command digest authority version입니다. key material은 encapsulated 상태로 유지됩니다. */
    val commandTombstoneKeyVersion: Int get() = commandTombstoneKey.version
    /**
     * `currentUserIdentityKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val currentUserIdentityKeyVersion: Int
        get() = checkNotNull(rotatingKeys[DigestPurpose.USER_IDENTITY]).current.version

    /** persisted data가 이 process가 읽을 수 없는 key version을 참조하면 fail-closed합니다. */
    /** persisted data가 이 process가 읽을 수 없는 key version을 참조하면 fail-closed합니다. */
    fun requireAvailable(purpose: DigestPurpose, version: Int) {
        version.requirePositiveNumber("version")

        when (purpose) {
            DigestPurpose.STABLE_DEDUP ->
                if (stableDedupKey.version != version) throw VoucherKeyMaterialUnavailableException()
            DigestPurpose.COMMAND_TOMBSTONE ->
                if (commandTombstoneKey.version != version) throw VoucherKeyMaterialUnavailableException()
            else                       ->
                checkNotNull(rotatingKeys[purpose]).require(version)
        }
    }

    init {
        require(rotatingKeys.keys == ROTATING_PURPOSES) {
            "every rotating digest purpose requires an explicit key ring"
        }
    }

    /**
     * stableDedup은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param code 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun stableDedup(tenantId: String, code: CanonicalVoucherCode): VoucherDigest =
        digestWithKey(DigestPurpose.STABLE_DEDUP, stableDedupKey, tenantId.bytes(), code.rawBytes())

    /**
     * verification은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun verification(
        tenantId: String,
        campaignId: UUID,
        allocationId: UUID,
        code: CanonicalVoucherCode,
    ): VoucherDigest =
        rotatingDigest(
            purpose = DigestPurpose.VERIFICATION,
            tenantId.bytes(), campaignId.bytes(), allocationId.bytes(), code.rawBytes()
        )

    /**
     * matchesVerification은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun matchesVerification(
        tenantId: String,
        campaignId: UUID,
        allocationId: UUID,
        code: CanonicalVoucherCode,
        expected: VoucherDigest,
    ): Boolean =
        matches(
            purpose = DigestPurpose.VERIFICATION,
            expected = expected,
            tenantId.bytes(), campaignId.bytes(), allocationId.bytes(), code.rawBytes()
        )

    /**
     * userIdentity은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param campaignId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param canonicalUser 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun userIdentity(tenantId: String, campaignId: UUID, canonicalUser: String): VoucherDigest =
        userIdentity(tenantId, campaignId, canonicalUser, currentUserIdentityKeyVersion)

    /**
     * userIdentity은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun userIdentity(
        tenantId: String,
        campaignId: UUID,
        canonicalUser: String,
        keyVersion: Int,
    ): VoucherDigest =
        digestWithKey(
            DigestPurpose.USER_IDENTITY,
            checkNotNull(rotatingKeys[DigestPurpose.USER_IDENTITY]).require(keyVersion),
            tenantId.bytes(),
            campaignId.bytes(),
            canonicalUser.bytes(),
        )

    /**
     * commandTombstone은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param operation 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param rawIdempotencyKey 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun commandTombstone(tenantId: String, operation: String, rawIdempotencyKey: String): VoucherDigest =
        digestWithKey(
            DigestPurpose.COMMAND_TOMBSTONE,
            commandTombstoneKey,
            tenantId.bytes(),
            operation.bytes(),
            rawIdempotencyKey.bytes(),
        )

    /**
     * redisSignal은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param campaignId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param operation 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param signal 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun redisSignal(tenantId: String, campaignId: UUID, operation: String, signal: String): VoucherDigest =
        rotatingDigest(
            DigestPurpose.REDIS_SIGNAL,
            tenantId.bytes(),
            campaignId.bytes(),
            operation.bytes(),
            signal.bytes(),
        )

    /**
     * audit은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param operation 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param requestMaterial 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun audit(tenantId: String, operation: String, requestMaterial: String): VoucherDigest =
        rotatingDigest(DigestPurpose.AUDIT, tenantId.bytes(), operation.bytes(), requestMaterial.bytes())

    /**
     * matchesAudit은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun matchesAudit(
        tenantId: String,
        operation: String,
        requestMaterial: String,
        expected: VoucherDigest,
    ): Boolean = matches(
        DigestPurpose.AUDIT,
        expected,
        tenantId.bytes(),
        operation.bytes(),
        requestMaterial.bytes(),
    )

    /**
     * matchesStableDedup은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param code 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param expected 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun matchesStableDedup(tenantId: String, code: CanonicalVoucherCode, expected: VoucherDigest): Boolean {
        if (expected.purpose != DigestPurpose.STABLE_DEDUP) return false
        if (expected.keyVersion != stableDedupKey.version) throw VoucherKeyMaterialUnavailableException()
        val actual = CanonicalFields.hmac(stableDedupKey, DigestPurpose.STABLE_DEDUP, tenantId.bytes(), code.rawBytes())

        return MessageDigest.isEqual(actual, expected.copyBytes())
    }

    /**
     * rotatingDigest은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param purpose 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param fields 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun rotatingDigest(purpose: DigestPurpose, vararg fields: ByteArray): VoucherDigest {
        val key = checkNotNull(rotatingKeys[purpose]).current
        return digestWithKey(purpose, key, *fields)
    }

    /**
     * matches은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param purpose 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param expected 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param fields 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun matches(purpose: DigestPurpose, expected: VoucherDigest, vararg fields: ByteArray): Boolean {
        if (expected.purpose != purpose) return false
        val key = checkNotNull(rotatingKeys[purpose]).require(expected.keyVersion)
        val actual = CanonicalFields.hmac(key, purpose, *fields)

        return MessageDigest.isEqual(actual, expected.copyBytes())
    }

    companion object: KLogging() {
        /**
         * `ROTATING_PURPOSES`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private val ROTATING_PURPOSES = setOf(
            DigestPurpose.VERIFICATION,
            DigestPurpose.USER_IDENTITY,
            DigestPurpose.REDIS_SIGNAL,
            DigestPurpose.AUDIT,
        )
    }
}

/**
 * digestWithKey은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param purpose 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 * @param key 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 * @param fields 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun digestWithKey(purpose: DigestPurpose, key: DigestKey, vararg fields: ByteArray): VoucherDigest =
    VoucherDigest(purpose, key.version, CanonicalFields.hmac(key, purpose, *fields))

/**
 * CanonicalFields은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal object CanonicalFields {
    /**
     * `DIGEST_DOMAIN`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val DIGEST_DOMAIN = "voucher-pool-digest-v1".bytes()

    /**
     * hmac은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param key 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param purpose 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param fields 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun hmac(key: DigestKey, purpose: DigestPurpose, vararg fields: ByteArray): ByteArray {
        val material = key.copyMaterial()

        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(material, "HmacSHA256"))
            update(mac, DIGEST_DOMAIN)
            update(mac, purpose.name.bytes())
            fields.forEach { update(mac, it) }
            mac.doFinal()
        } finally {
            material.fill(0)
            fields.forEach { it.fill(0) }
        }
    }

    /**
     * encode은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param domain 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param fields 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun encode(domain: String, vararg fields: ByteArray): ByteArray {
        val domainBytes = domain.bytes()
        val size = Int.SIZE_BYTES + domainBytes.size + fields.sumOf { Int.SIZE_BYTES + it.size }

        return ByteBuffer.allocate(size).apply {
            putField(domainBytes)
            fields.forEach { field -> putField(field) }
        }.array()
    }

    /**
     * update은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param mac 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param field 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun update(mac: Mac, field: ByteArray) {
        mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(field.size).array())
        mac.update(field)
    }

/**
 * putField은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun ByteBuffer.putField(field: ByteArray) {
        putInt(field.size)
        put(field)
    }
}

/**
 * bytes은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun String.bytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)

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
 * rawBytes은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun CanonicalVoucherCode.rawBytes(): ByteArray =
    withRawValue { it.bytes() }
