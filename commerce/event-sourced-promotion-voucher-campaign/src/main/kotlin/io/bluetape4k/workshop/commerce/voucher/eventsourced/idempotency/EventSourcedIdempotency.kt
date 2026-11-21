package io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency

import io.bluetape4k.support.requireBeTrue
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import java.io.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.*

/**
 * `SHA_256_DIGEST_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val SHA_256_DIGEST_LENGTH = 64
/**
 * `OWNER_TOKEN_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val OWNER_TOKEN_BYTES = 32
/**
 * `MIN_HTTP_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val MIN_HTTP_STATUS = 100
/**
 * `MAX_HTTP_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val MAX_HTTP_STATUS = 599

/** canonical SHA-256 hex digest입니다. raw identity, key, owner-token material은 persistence에 도달하지 않습니다. */
/** canonical SHA-256 hex digest입니다. raw identity, key, owner-token material은 persistence에 도달하지 않습니다. */
@JvmInline
internal value class ReceiptDigest private constructor(
    /**
     * `value`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val value: String,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /** persistence 전에 이미 검증된 canonical digest를 재구성합니다. */
        /** persistence 전에 이미 검증된 canonical digest를 재구성합니다. */
        operator fun invoke(value: String): ReceiptDigest = ReceiptDigest(value.requireNotBlank("value"))

        /** persistence 전에 이미 검증된 canonical digest를 재구성합니다. */
        /** persistence 전에 이미 검증된 canonical digest를 재구성합니다. */
        fun of(value: String): ReceiptDigest = ReceiptDigest(value.requireNotBlank("value"))

        /**
         * sha256은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun sha256(value: String): ReceiptDigest =
            value
                .requireNotBlank("digestMaterial")
                .toUtf8Bytes()
                .let { MessageDigest.getInstance("SHA-256").digest(it) }
                .let(HexFormat.of()::formatHex)
                .let(::ReceiptDigest)
    }

    init {
        value.length.requireEquals(SHA_256_DIGEST_LENGTH, "digest.length")
        value.all { it in '0'..'9' || it in 'a'..'f' }.requireBeTrue("digest.lowercaseHex")
    }
}

/** secret acquisition capability입니다. [digest]만 저장합니다. */
/** secret acquisition capability입니다. [digest]만 저장합니다. */
@JvmInline
internal value class ReceiptOwnerToken private constructor(
    /**
     * `value`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val value: String,
): Serializable {
    /**
     * digest은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun digest(): ReceiptDigest = ReceiptDigest.sha256("voucher-receipt-owner-v1\u0000$value")

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * random은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param random 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun random(random: SecureRandom = SECURE_RANDOM): ReceiptOwnerToken =
            ByteArray(OWNER_TOKEN_BYTES)
                .also(random::nextBytes)
                .let(HexFormat.of()::formatHex)
                .let(::ReceiptOwnerToken)

        /**
         * `SECURE_RANDOM`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val SECURE_RANDOM = SecureRandom()
    }
}

/** idempotent command receipt 하나의 immutable identity입니다. raw credential은 절대 저장하지 않습니다. */
/** idempotent command receipt 하나의 immutable identity입니다. raw credential은 절대 저장하지 않습니다. */
@ConsistentCopyVisibility
internal data class ReceiptScope private constructor(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: TenantId,
    /**
     * `principalDigest`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val principalDigest: ReceiptDigest,
    /**
     * `operation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val operation: String,
    /**
     * `resourceId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId: String,
    /**
     * `keyDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val keyDigest: ReceiptDigest,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * invoke은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        operator fun invoke(
            tenantId: TenantId,
            principalDigest: ReceiptDigest,
            operation: String,
            resourceId: String,
            keyDigest: ReceiptDigest,
        ): ReceiptScope =
            ReceiptScope(
                tenantId = tenantId,
                principalDigest = principalDigest,
                operation = operation.requireNotBlank("operation"),
                resourceId = resourceId.requireNotBlank("resourceId"),
                keyDigest = keyDigest,
            )
    }
}

@ConsistentCopyVisibility
/**
 * TerminalKeyVersions은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class TerminalKeyVersions private constructor(
    /**
     * `hmac`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val hmac: Int,
    /**
     * `generationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generationKeyVersion: Int?,
    /**
     * `verificationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val verificationKeyVersion: Int?,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * invoke은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        operator fun invoke(
            hmac: Int = 1,
            generationKeyVersion: Int? = null,
            verificationKeyVersion: Int? = null,
        ): TerminalKeyVersions {
            /**
             * `validHmac`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validHmac = hmac.requirePositiveNumber("hmacKeyVersion")
            require((generationKeyVersion == null) == (verificationKeyVersion == null)) {
                "generationKeyVersion and verificationKeyVersion 둘 다 null 이거나, 둘 다 not null 이어야합니다."
            }
            return TerminalKeyVersions(
                hmac = validHmac,
                generationKeyVersion = generationKeyVersion?.requirePositiveNumber("generationKeyVersion"),
                verificationKeyVersion = verificationKeyVersion?.requirePositiveNumber("verificationKeyVersion"),
            )
        }
    }
}

/** 닫힌 response descriptor입니다. voucher code가 아니라 allocation identity와 key version만 의도적으로 저장합니다. */
/** 닫힌 response descriptor입니다. voucher code가 아니라 allocation identity와 key version만 의도적으로 저장합니다. */
@ConsistentCopyVisibility
internal data class TerminalDescriptor private constructor(
    /**
     * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: ReceiptOutcome,
    /**
     * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: Int,
    /**
     * `keyVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val keyVersions: TerminalKeyVersions,
    /**
     * `allocationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val allocationId: UUID?,
    /**
     * `observedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val observedAt: Instant?,
    /**
     * `streamPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val streamPosition: Long?,
): Serializable {
    /**
     * `hmacKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val hmacKeyVersion: Int get() = keyVersions.hmac
    /**
     * `generationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generationKeyVersion: Int? get() = keyVersions.generationKeyVersion
    /**
     * `verificationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val verificationKeyVersion: Int? get() = keyVersions.verificationKeyVersion

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * invoke은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        operator fun invoke(
            outcome: ReceiptOutcome,
            status: Int,
            keyVersions: TerminalKeyVersions = TerminalKeyVersions(),
            allocationId: UUID? = null,
        ): TerminalDescriptor {
            /**
             * `validStatus`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validStatus = status.requireInRange(MIN_HTTP_STATUS, MAX_HTTP_STATUS, "status")
            require(keyVersions.generationKeyVersion == null || allocationId != null) {
                "keyVersions.generationKeyVersion 이 null 이거나 allocationId 가 not null 이어야 합니다."
            }

            return TerminalDescriptor(outcome, validStatus, keyVersions, allocationId, null, null)
        }
    }

    /**
     * withObservedAt은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param instant 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun withObservedAt(instant: Instant): TerminalDescriptor =
        TerminalDescriptor(
            outcome = outcome,
            status = status,
            keyVersions = keyVersions,
            allocationId = allocationId,
            observedAt = instant,
            streamPosition = streamPosition,
        )

    /**
     * withStreamPosition은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param position 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun withStreamPosition(position: Long): TerminalDescriptor =
        TerminalDescriptor(
            outcome = outcome,
            status = status,
            keyVersions = keyVersions,
            allocationId = allocationId,
            observedAt = observedAt,
            streamPosition = position.requirePositiveNumber("streamPosition"),
        )

    /**
     * replayWith은 이벤트 소싱 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param keyVersionAvailable 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun replayWith(keyVersionAvailable: (Int) -> Boolean): TerminalReplay {
        val generationVersion = generationKeyVersion
        return if (keyVersionAvailable(hmacKeyVersion) &&
            (generationVersion == null || keyVersionAvailable(generationVersion))
        ) {
            TerminalReplay.Replay(this)
        } else {
            TerminalReplay.KeyUnavailable
        }
    }
}

/**
 * ReceiptOutcome은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class ReceiptOutcome {
    /**
     * `ReceiptOutcome.CAMPAIGN_CREATED`은 ReceiptOutcome 업무 상태의 `CAMPAIGN_CREATED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_CREATED,
    /**
     * `ReceiptOutcome.CAMPAIGN_ACTIVATED`은 ReceiptOutcome 업무 상태의 `CAMPAIGN_ACTIVATED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_ACTIVATED,
    /**
     * `ReceiptOutcome.CAMPAIGN_NOT_FOUND`은 ReceiptOutcome 업무 상태의 `CAMPAIGN_NOT_FOUND` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_NOT_FOUND,
    /**
     * `ReceiptOutcome.CAMPAIGN_NOT_ACTIVE`은 ReceiptOutcome 업무 상태의 `CAMPAIGN_NOT_ACTIVE` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_NOT_ACTIVE,
    /**
     * `ReceiptOutcome.CAMPAIGN_NOT_STARTED`은 ReceiptOutcome 업무 상태의 `CAMPAIGN_NOT_STARTED` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_NOT_STARTED,
    /**
     * `ReceiptOutcome.CAMPAIGN_ENDED`은 ReceiptOutcome 업무 상태의 `CAMPAIGN_ENDED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답
     * 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_ENDED,
    /**
     * `ReceiptOutcome.CAPACITY_EXHAUSTED`은 ReceiptOutcome 업무 상태의 `CAPACITY_EXHAUSTED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAPACITY_EXHAUSTED,
    /**
     * `ReceiptOutcome.PER_USER_LIMIT_REACHED`은 ReceiptOutcome 업무 상태의 `PER_USER_LIMIT_REACHED`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    PER_USER_LIMIT_REACHED,
    /**
     * `ReceiptOutcome.STALE_REVISION`은 ReceiptOutcome 업무 상태의 `STALE_REVISION` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답
     * 분기를 선택하는 기준으로 사용합니다.
     */
    STALE_REVISION,
    /**
     * `ReceiptOutcome.VOUCHER_ALLOCATED`은 ReceiptOutcome 업무 상태의 `VOUCHER_ALLOCATED` 단계입니다. 허용된 상태
     * 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    VOUCHER_ALLOCATED,
    /**
     * `ReceiptOutcome.VOUCHER_REDEEMED`은 ReceiptOutcome 업무 상태의 `VOUCHER_REDEEMED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    VOUCHER_REDEEMED,
    /**
     * `ReceiptOutcome.VOUCHER_RELEASED`은 ReceiptOutcome 업무 상태의 `VOUCHER_RELEASED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    VOUCHER_RELEASED,
    /**
     * `ReceiptOutcome.VOUCHER_NOT_FOUND`은 ReceiptOutcome 업무 상태의 `VOUCHER_NOT_FOUND` 단계입니다. 허용된 상태
     * 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    VOUCHER_NOT_FOUND,
    /**
     * `ReceiptOutcome.INVALID_VOUCHER_CODE`은 ReceiptOutcome 업무 상태의 `INVALID_VOUCHER_CODE` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    INVALID_VOUCHER_CODE,
    /**
     * `ReceiptOutcome.VOUCHER_EXPIRED`은 ReceiptOutcome 업무 상태의 `VOUCHER_EXPIRED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    VOUCHER_EXPIRED,
    /**
     * `ReceiptOutcome.INVALID_TRANSITION`은 ReceiptOutcome 업무 상태의 `INVALID_TRANSITION` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    INVALID_TRANSITION,
    /**
     * `ReceiptOutcome.CONCURRENT_MODIFICATION`은 ReceiptOutcome 업무 상태의 `CONCURRENT_MODIFICATION`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CONCURRENT_MODIFICATION,
    /**
     * `ReceiptOutcome.DOMAIN_REJECTED`은 ReceiptOutcome 업무 상태의 `DOMAIN_REJECTED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    DOMAIN_REJECTED,
}

/**
 * ReceiptStatus은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class ReceiptStatus {
    /**
     * `ReceiptStatus.IN_PROGRESS`은 ReceiptStatus 업무 상태의 `IN_PROGRESS` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
     * 분기를 선택하는
     * 기준으로 사용합니다.
     */
    IN_PROGRESS,
    /**
     * `ReceiptStatus.SUCCEEDED`은 ReceiptStatus 업무 상태의 `SUCCEEDED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를
     * 선택하는
     * 기준으로 사용합니다.
     */
    SUCCEEDED,
    /**
     * `ReceiptStatus.FAILED`은 ReceiptStatus 업무 상태의 `FAILED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    FAILED,
}

/**
 * TerminalReplay은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal sealed interface TerminalReplay {
    /**
     * Replay은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Replay(val descriptor: TerminalDescriptor): TerminalReplay

    /**
     * KeyUnavailable은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data object KeyUnavailable: TerminalReplay
}

/**
 * ReceiptAcquireResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal sealed interface ReceiptAcquireResult {
    /**
     * Owner은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Owner(
        /**
         * `token`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val token: ReceiptOwnerToken,
        /**
         * `leaseDeadline`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val leaseDeadline: java.time.Instant,
    ): ReceiptAcquireResult

    /**
     * Replay은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Replay(val descriptor: TerminalDescriptor): ReceiptAcquireResult

    /**
     * InProgress은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class InProgress(val retryAfter: java.time.Duration): ReceiptAcquireResult

    /**
     * FingerprintConflict은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
     * 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data object FingerprintConflict: ReceiptAcquireResult
}
