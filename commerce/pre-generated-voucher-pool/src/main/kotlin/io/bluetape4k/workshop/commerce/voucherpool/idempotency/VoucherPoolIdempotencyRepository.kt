@file:Suppress(
    "LongParameterList", // Decision evaluation keeps its locked row and immutable command authority together.
    "MagicNumber", // PreparedStatement positions mirror the adjacent explicit SQL column order.
    "MaxLineLength",
    "ReturnCount", // Acquire exits immediately for mutually exclusive durable states.
    "TooGenericExceptionCaught", // Jackson may wrap malformed stored JSON in several runtime exception types.
    "TooManyFunctions",
)

package io.bluetape4k.workshop.commerce.voucherpool.idempotency

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.hashOf
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucherpool.domain.DescriptorAction
import io.bluetape4k.workshop.commerce.voucherpool.domain.TombstoneAction
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCatalog
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCommandTombstoneTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolHttpIdempotencyTable
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.util.*

/** idempotent command의 tenant 및 operation boundary입니다. */
/** idempotent command의 tenant 및 operation boundary입니다. */
internal data class CommandScope(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `operation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val operation: String,
): Serializable {

    init {
        tenantId.requireNotBlank("tenantId")
        operation.requireNotBlank("operation")
        require(tenantId.length <= MAX_TENANT_LENGTH) { "tenantId is too long" }
        require(operation.length <= MAX_OPERATION_LENGTH) { "operation is too long" }
    }

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
        /**
         * `MAX_TENANT_LENGTH`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        private const val MAX_TENANT_LENGTH = 64
        /**
         * `MAX_OPERATION_LENGTH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private const val MAX_OPERATION_LENGTH = 64
    }
}

/** replay window 동안 보존되는 닫힌 code-free response shape입니다. */
/** replay window 동안 보존되는 닫힌 code-free response shape입니다. */
@ConsistentCopyVisibility
internal data class SafeResponseDescriptor private constructor(
    /**
     * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: Int,
    /**
     * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: String?,
    /**
     * `effectId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val effectId: UUID?,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long?,
    /**
     * `terminalCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val terminalCode: VoucherPoolErrorCode?,
): Serializable {

    init {
        require(status in MIN_HTTP_STATUS..MAX_HTTP_STATUS) { "descriptor status must be a valid HTTP status" }
        require(revision == null || revision >= 0) { "descriptor revision must be non-negative" }
        require(outcome == null || OUTCOME.matches(outcome)) { "descriptor outcome must be a bounded stable code" }
        if (terminalCode == null) {
            require(outcome != null && effectId != null && revision != null) {
                "success descriptor requires outcome, effect id, and revision"
            }
            require(status in MIN_SUCCESS_STATUS..MAX_SUCCESS_STATUS) {
                "success descriptor status must be 2xx or 3xx"
            }
        } else {
            require(outcome == null && effectId == null && revision == null) {
                "terminal descriptor must not retain success fields"
            }
            /**
             * `semantics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val semantics = VoucherPoolErrorCatalog[terminalCode]
            require(
                semantics.httpStatus == status &&
                        semantics.descriptorAction == DescriptorAction.STORE &&
                        semantics.tombstoneAction == TombstoneAction.STORE,
            ) { "terminal descriptor requires terminal stored error semantics" }
        }
    }

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
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
        /**
         * `MIN_SUCCESS_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        private const val MIN_SUCCESS_STATUS = 200
        /**
         * `MAX_SUCCESS_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        private const val MAX_SUCCESS_STATUS = 399
        /**
         * `OUTCOME`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        private val OUTCOME = Regex("[A-Z][A-Z0-9_]{0,63}")

        /**
         * success은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param status 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param outcome 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param effectId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param revision 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun success(status: Int, outcome: String, effectId: UUID, revision: Long): SafeResponseDescriptor =
            SafeResponseDescriptor(status, outcome, effectId, revision, null)

        /**
         * terminal은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param status 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         * @param terminalCode 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun terminal(status: Int, terminalCode: VoucherPoolErrorCode): SafeResponseDescriptor =
            SafeResponseDescriptor(status, null, null, null, terminalCode)

        /**
         * restore은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        internal fun restore(
            status: Int,
            outcome: String?,
            effectId: UUID?,
            revision: Long?,
            terminalCode: VoucherPoolErrorCode?,
        ): SafeResponseDescriptor =
            SafeResponseDescriptor(status, outcome, effectId, revision, terminalCode)
    }
}

/** full descriptor가 만료된 뒤 보존되는 minimal tenant-lifetime outcome입니다. */
/** full descriptor가 만료된 뒤 보존되는 minimal tenant-lifetime outcome입니다. */
@ConsistentCopyVisibility
internal data class EffectReference private constructor(
    /**
     * `effectId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val effectId: UUID?,
    /**
     * `terminalCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val terminalCode: VoucherPoolErrorCode?,
): Serializable {
    init {
        require((effectId == null) != (terminalCode == null)) { "effect reference requires exactly one terminal value" }
    }

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * effect은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param effectId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun effect(effectId: UUID): EffectReference =
            EffectReference(effectId, null)

        /**
         * terminal은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param code 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun terminal(code: VoucherPoolErrorCode): EffectReference =
            EffectReference(null, code)
    }
}

/** secret owner capability입니다. diagnostics와 persistence는 digest만 노출합니다. */
/** secret owner capability입니다. diagnostics와 persistence는 digest만 노출합니다. */
internal class IdempotencyOwner internal constructor(
    /**
     * `scope`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scope: CommandScope,
    scopedKeyDigest: ByteArray,
    /**
     * `keyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val keyVersion: Int,
    /**
     * `fingerprint`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fingerprint: CommandFingerprint,
    ownerTokenDigest: ByteArray,
) {

    companion object: KLogging()

    /**
     * `scopedKeyDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val scopedKeyDigest = scopedKeyDigest.copyOf()
    /**
     * `ownerTokenDigest`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val ownerTokenDigest = ownerTokenDigest.copyOf()

    init {
        keyVersion.requirePositiveNumber("keyVersion")
        ownerTokenDigest.size.requireEquals(OWNER_DIGEST_BYTES) { "owner capability digest must contain 256 bits" }
    }

    /**
     * copyScopedKeyDigest은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun copyScopedKeyDigest(): ByteArray = scopedKeyDigest.copyOf()

    /**
     * copyTokenDigest은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    internal fun copyTokenDigest(): ByteArray = ownerTokenDigest.copyOf()

    /**
     * equals은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param other 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun equals(other: Any?): Boolean =
        other is IdempotencyOwner &&
                scope == other.scope &&
                MessageDigest.isEqual(scopedKeyDigest, other.scopedKeyDigest) &&
                MessageDigest.isEqual(copyTokenDigest(), other.copyTokenDigest())

    /**
     * hashCode은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun hashCode(): Int = hashOf(scope, scopedKeyDigest.contentHashCode())

    /**
     * toString은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun toString(): String =
        "IdempotencyOwner(operation=${scope.operation}, scopedKeyDigest=[REDACTED], capability=[REDACTED])"
}

/**
 * IdempotencyDecision은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal sealed interface IdempotencyDecision: Serializable {
    /**
     * Execute은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Execute(val owner: IdempotencyOwner): IdempotencyDecision {
        companion object {
            /**
             * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Replay은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Replay(val descriptor: SafeResponseDescriptor): IdempotencyDecision {
        companion object {
            /**
             * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Expired은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Expired(
        /**
         * `effectId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val effectId: UUID?,
        /**
         * `terminalCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val terminalCode: VoucherPoolErrorCode?,
    ): IdempotencyDecision {
        companion object {
            /**
             * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * InProgress은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class InProgress(val retryAfter: Duration): IdempotencyDecision {
        companion object {
            /**
             * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * FingerprintConflict은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data object FingerprintConflict: IdempotencyDecision {
        /**
         * readResolve은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        private fun readResolve(): Any = FingerprintConflict
    }
}

/**
 * VoucherPoolIdempotencyRepository은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface VoucherPoolIdempotencyRepository {

    /**
     * acquire은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param rawKey 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param fingerprint 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun acquire(scope: CommandScope, rawKey: String, fingerprint: CommandFingerprint): IdempotencyDecision

    /**
     * lockOwnerForExecution은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param owner 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun lockOwnerForExecution(owner: IdempotencyOwner)

    /**
     * finalize은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param owner 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param descriptor 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param effect 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun finalize(owner: IdempotencyOwner, descriptor: SafeResponseDescriptor, effect: EffectReference): Boolean

    /**
     * releaseRetryable은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param owner 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun releaseRetryable(owner: IdempotencyOwner): Boolean

    /**
     * purgeDescriptors은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun purgeDescriptors(limit: Int): Int
}

/**
 * IdempotencyOwnershipLostException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을
 * 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class IdempotencyOwnershipLostException:
    IllegalStateException("idempotency command ownership is no longer active")

/**
 * IdempotencyTombstoneKeyVersionException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이,
 * 저장·발행
 * 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class IdempotencyTombstoneKeyVersionException:
    IllegalStateException("idempotency tombstone key version is unsupported")

/**
 * IdempotencyDescriptorCorruptedException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이,
 * 저장·발행
 * 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class IdempotencyDescriptorCorruptedException:
    IllegalStateException("stored idempotency descriptor is invalid")

/**
 * IdempotencyTombstoneIntegrityException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class IdempotencyTombstoneIntegrityException:
    IllegalStateException("idempotency tombstone integrity check failed")

/**
 * owner lease, safe replay descriptor, tenant-lifetime command fence를 담당하는 PostgreSQL authority입니다.
 *
 * raw JDBC는 PostgreSQL lock과 JSONB operation을 caller의 기존 Exposed/Spring transaction 안에 유지합니다.
 * connection, commit, rollback ownership은 이 repository boundary를 넘지 않습니다.
 */
@Suppress("SqlResolve")
internal class JdbcVoucherPoolIdempotencyRepository(
    /**
     * `digests`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val digests: VoucherDigestService,
    /**
     * `lease`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val lease: Duration = DEFAULT_LEASE_SECONDS.seconds(),
    /**
     * `commandTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val commandTimeout: Duration = DEFAULT_COMMAND_TIMEOUT_MINUTES.minutes(),
    /**
     * `descriptorRetention`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val descriptorRetention: Duration = DEFAULT_DESCRIPTOR_RETENTION_HOURS.hours(),
    /**
     * `random`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val random: SecureRandom = SecureRandom(),
): VoucherPoolIdempotencyRepository {

    companion object: KLogging() {
        /**
         * `DEFAULT_LEASE_SECONDS`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        private const val DEFAULT_LEASE_SECONDS = 30L
        /**
         * `DEFAULT_COMMAND_TIMEOUT_MINUTES`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private const val DEFAULT_COMMAND_TIMEOUT_MINUTES = 1L
        /**
         * `DEFAULT_DESCRIPTOR_RETENTION_HOURS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val DEFAULT_DESCRIPTOR_RETENTION_HOURS = 24L
        /**
         * `MIN_RAW_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        private const val MIN_RAW_KEY_LENGTH = 8
        /**
         * `MAX_RAW_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        private const val MAX_RAW_KEY_LENGTH = 200
        /**
         * `MAX_PURGE_LIMIT`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private const val MAX_PURGE_LIMIT = 1_000
        /**
         * `OWNER_TOKEN_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private const val OWNER_TOKEN_BYTES = 32
        /**
         * `SUCCESS_DESCRIPTOR_FIELDS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val SUCCESS_DESCRIPTOR_FIELDS = setOf("status", "outcome", "effectId", "revision")
        /**
         * `TERMINAL_DESCRIPTOR_FIELDS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val TERMINAL_DESCRIPTOR_FIELDS = setOf("status", "terminalCode")
    }

    init {
        requireDuration(lease, "lease")
        requireDuration(commandTimeout, "commandTimeout")
        requireDuration(descriptorRetention, "descriptorRetention")
        lease.requireLe(commandTimeout) { "lease must not exceed commandTimeout" }
    }

    /**
     * acquire은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun acquire(
        scope: CommandScope,
        rawKey: String,
        fingerprint: CommandFingerprint,
    ): IdempotencyDecision {
        require(rawKey.length in MIN_RAW_KEY_LENGTH..MAX_RAW_KEY_LENGTH) {
            "Idempotency-Key must contain $MIN_RAW_KEY_LENGTH..$MAX_RAW_KEY_LENGTH characters"
        }
        require(rawKey.none(Char::isISOControl)) { "Idempotency-Key must not contain control characters" }

        val connection = currentConnection()
        val keyDigest = digests.commandTombstone(scope.tenantId, scope.operation, rawKey)
        check(keyDigest.purpose == DigestPurpose.COMMAND_TOMBSTONE)

        lockRow(connection, scope, keyDigest.copyBytes())
            ?.let { current ->
                return decideCurrent(connection, scope, keyDigest, fingerprint, current)
            }
        findTombstone(scope, keyDigest.copyBytes())
            ?.let { tombstone ->
                return tombstone.decision(fingerprint)
            }

        val owner = newOwner(scope, keyDigest, fingerprint)
        if (insertOwner(connection, owner)) {
            log.debug { "voucher_pool_idempotency_acquired operation=${scope.operation}" }
            return IdempotencyDecision.Execute(owner)
        }

        val current = lockRow(connection, scope, keyDigest.copyBytes())
            ?: findTombstone(scope, keyDigest.copyBytes())?.let { return it.decision(fingerprint) }
            ?: error("idempotency row disappeared")

        return decideCurrent(connection, scope, keyDigest, fingerprint, current, owner)
    }

    /**
     * decideCurrent은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun decideCurrent(
        connection: Connection,
        scope: CommandScope,
        keyDigest: VoucherDigest,
        fingerprint: CommandFingerprint,
        current: IdempotencyRow,
        proposedOwner: IdempotencyOwner? = null,
    ): IdempotencyDecision {
        if (!current.fingerprint.matches(fingerprint)) {
            log.warn { "voucher_pool_idempotency_fingerprint_conflict operation=${scope.operation}" }
            return IdempotencyDecision.FingerprintConflict
        }
        return when (current.status) {
            IdempotencyStatus.COMPLETED        ->
                current.completedDecision(scope = scope, scopedKeyDigest = keyDigest.copyBytes())

            IdempotencyStatus.OWNED            ->
                if (current.active) {
                    IdempotencyDecision.InProgress(current.retryAfterMillis.millis())
                } else {
                    takeOver(
                        connection = connection,
                        owner = proposedOwner ?: newOwner(scope, keyDigest, fingerprint),
                        expectedRevision = current.revision
                    )
                }

            IdempotencyStatus.RETRYABLE_FAILED ->
                takeOver(
                    connection = connection,
                    owner = proposedOwner ?: newOwner(scope, keyDigest, fingerprint),
                    expectedRevision = current.revision
                )
        }
    }

    /**
     * lockOwnerForExecution은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param owner 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun lockOwnerForExecution(owner: IdempotencyOwner) {
        val active = VoucherPoolHttpIdempotencyTable
            .selectAll()
            .where {
                (VoucherPoolHttpIdempotencyTable.tenantId eq owner.scope.tenantId) and
                        (VoucherPoolHttpIdempotencyTable.operation eq owner.scope.operation) and
                        (VoucherPoolHttpIdempotencyTable.scopedKeyDigest eq owner.copyScopedKeyDigest()) and
                        (VoucherPoolHttpIdempotencyTable.fingerprint eq owner.fingerprint.copyBytes()) and
                        (VoucherPoolHttpIdempotencyTable.status eq IdempotencyStatus.OWNED.name) and
                        (VoucherPoolHttpIdempotencyTable.ownerTokenDigest eq owner.copyTokenDigest()) and
                        (VoucherPoolHttpIdempotencyTable.leaseUntil greaterEq CurrentTimestamp) and
                        (VoucherPoolHttpIdempotencyTable.commandDeadline greaterEq CurrentTimestamp)
            }
            .forUpdate()
            .limit(1)
            .any()
        if (!active) ownershipLost()
    }

    /**
     * finalize은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun finalize(
        owner: IdempotencyOwner,
        descriptor: SafeResponseDescriptor,
        effect: EffectReference,
    ): Boolean {
        requireDescriptorMatchesEffect(descriptor, effect)
        val connection = currentConnection()
        // descriptor는 PostgreSQL JSONB cast와 statement_timestamp() 기반 만료 계산을 함께 원자화해야 합니다.
        val updated = connection.prepareStatement(
            """UPDATE voucher_pool_http_idempotency
                SET status='COMPLETED',owner_token_digest=NULL,lease_until=NULL,descriptor=?::jsonb,
                    completed_at=statement_timestamp(),
                    expires_at=statement_timestamp()+(? * interval '1 millisecond'),revision=revision+1
                WHERE tenant_id=? AND operation=? AND scoped_key_digest=? AND fingerprint=?
                  AND status='OWNED' AND owner_token_digest=?
                  AND lease_until>=statement_timestamp() AND command_deadline>=statement_timestamp()""".trimIndent(),
        ).use { statement ->
            statement.setString(1, descriptor.toJson())
            statement.setLong(2, descriptorRetention.toMillis())
            owner.bindScope(statement, start = 3)
            statement.setBytes(6, owner.fingerprint.copyBytes())
            statement.setBytes(7, owner.copyTokenDigest())
            statement.executeUpdate()
        }
        if (updated == 0) ownershipLost()

        insertOrVerifyTombstone(owner, effect)
        log.debug { "voucher_pool_idempotency_finalized operation=${owner.scope.operation}" }
        return true
    }

    /**
     * releaseRetryable은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param owner 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun releaseRetryable(owner: IdempotencyOwner): Boolean {
        val released = VoucherPoolHttpIdempotencyTable.update({
            (VoucherPoolHttpIdempotencyTable.tenantId eq owner.scope.tenantId) and
                    (VoucherPoolHttpIdempotencyTable.operation eq owner.scope.operation) and
                    (VoucherPoolHttpIdempotencyTable.scopedKeyDigest eq owner.copyScopedKeyDigest()) and
                    (VoucherPoolHttpIdempotencyTable.fingerprint eq owner.fingerprint.copyBytes()) and
                    (VoucherPoolHttpIdempotencyTable.status eq IdempotencyStatus.OWNED.name) and
                    (VoucherPoolHttpIdempotencyTable.ownerTokenDigest eq owner.copyTokenDigest())
        }) {
            it[VoucherPoolHttpIdempotencyTable.status] = IdempotencyStatus.RETRYABLE_FAILED.name
            it[VoucherPoolHttpIdempotencyTable.ownerTokenDigest] = null
            it[VoucherPoolHttpIdempotencyTable.leaseUntil] = null
            it[VoucherPoolHttpIdempotencyTable.revision] = VoucherPoolHttpIdempotencyTable.revision + 1
        }
            .let { it == 1 }
        log.debug { "voucher_pool_idempotency_retryable_release operation=${owner.scope.operation} released=$released" }
        return released
    }

    /**
     * purgeDescriptors은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun purgeDescriptors(limit: Int): Int {
        limit.requireInRange(1, MAX_PURGE_LIMIT, "limit")
        val connection = currentConnection()
        // tombstone과 idempotency row를 함께 `SKIP LOCKED`로 선점하는 bounded purge는 PostgreSQL 전용 경계입니다.
        val candidates = connection.prepareStatement(
            """SELECT i.tenant_id,i.operation,i.scoped_key_digest,i.fingerprint,
                       t.key_version,t.effect_id,t.terminal_code
                FROM voucher_pool_http_idempotency i
                JOIN voucher_pool_command_tombstones t
                  ON t.tenant_id=i.tenant_id AND t.operation=i.operation
                 AND t.scoped_key_digest=i.scoped_key_digest AND t.fingerprint=i.fingerprint
                WHERE i.status='COMPLETED' AND i.descriptor IS NOT NULL
                  AND i.expires_at<=statement_timestamp()
                ORDER BY i.expires_at,i.tenant_id,i.operation LIMIT ? FOR UPDATE OF i,t SKIP LOCKED""".trimIndent(),
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            PurgeCandidate(
                                tenantId = result.getString(1),
                                operation = result.getString(2),
                                scopedKeyDigest = result.getBytes(3),
                                fingerprint = result.getBytes(4),
                                tombstone = TombstoneRow(
                                    keyVersion = result.getInt(5),
                                    fingerprint = result.getBytes(4),
                                    effectId = result.getObject(6, UUID::class.java),
                                    terminalCode = result.getString(7)?.let(VoucherPoolErrorCode::valueOf),
                                ),
                            ),
                        )
                    }
                }
            }
        }
        var purged = 0
        candidates.forEach { candidate ->
            requireCurrentTombstoneVersion(candidate.tombstone)
            if (!MessageDigest.isEqual(candidate.tombstone.fingerprint, candidate.fingerprint)) {
                log.warn { "voucher_pool_idempotency_purge_fenced operation=${candidate.operation}" }
            } else {
                purged += clearDescriptor(candidate)
            }
        }
        log.debug { "voucher_pool_idempotency_descriptors_purged count=$purged" }
        return purged
    }

    /**
     * insertOwner은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param owner 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun insertOwner(connection: Connection, owner: IdempotencyOwner): Boolean =
        // lease/deadline/retention의 statement_timestamp() 계산과 conflict 무시를 한 INSERT로 유지합니다.
        connection.prepareStatement(
            """INSERT INTO voucher_pool_http_idempotency
                (tenant_id,operation,scoped_key_digest,fingerprint,status,owner_token_digest,lease_until,
                 command_deadline,descriptor,expires_at,revision)
                VALUES (?,?,?,?,'OWNED',?,statement_timestamp()+(? * interval '1 millisecond'),
                    statement_timestamp()+(? * interval '1 millisecond'),NULL,
                    statement_timestamp()+(? * interval '1 millisecond'),0)
                ON CONFLICT DO NOTHING""".trimIndent(),
        ).use { statement ->
            owner.bindScope(statement, start = 1)
            statement.setBytes(4, owner.fingerprint.copyBytes())
            statement.setBytes(5, owner.copyTokenDigest())
            statement.setLong(6, lease.toMillis())
            statement.setLong(7, commandTimeout.toMillis())
            statement.setLong(8, descriptorRetention.toMillis())
            statement.executeUpdate() == 1
        }

    /**
     * takeOver은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param owner 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param expectedRevision 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun takeOver(connection: Connection, owner: IdempotencyOwner, expectedRevision: Long): IdempotencyDecision {
        // owner takeover는 여러 만료 시각을 같은 statement snapshot으로 계산하는 CAS이므로 raw SQL을 유지합니다.
        val updated = connection.prepareStatement(
            """UPDATE voucher_pool_http_idempotency
                SET status='OWNED',owner_token_digest=?,lease_until=statement_timestamp()+(? * interval '1 millisecond'),
                    command_deadline=statement_timestamp()+(? * interval '1 millisecond'),descriptor=NULL,completed_at=NULL,
                    expires_at=statement_timestamp()+(? * interval '1 millisecond'),revision=revision+1
                WHERE tenant_id=? AND operation=? AND scoped_key_digest=? AND fingerprint=? AND revision=?
                  AND (status='RETRYABLE_FAILED' OR (status='OWNED' AND lease_until<=statement_timestamp()))""".trimIndent(),
        ).use { statement ->
            statement.setBytes(1, owner.copyTokenDigest())
            statement.setLong(2, lease.toMillis())
            statement.setLong(3, commandTimeout.toMillis())
            statement.setLong(4, descriptorRetention.toMillis())
            owner.bindScope(statement, start = 5)
            statement.setBytes(8, owner.fingerprint.copyBytes())
            statement.setLong(9, expectedRevision)
            statement.executeUpdate()
        }
        check(updated == 1) { "locked idempotency owner takeover lost its revision" }
        log.warn { "voucher_pool_idempotency_owner_taken_over operation=${owner.scope.operation}" }
        return IdempotencyDecision.Execute(owner)
    }

    /**
     * lockRow은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param scopedKeyDigest 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun lockRow(connection: Connection, scope: CommandScope, scopedKeyDigest: ByteArray): IdempotencyRow? =
        // JSONB descriptor와 retry milliseconds projection을 잠금과 함께 계산해야 하므로 DSL로 분해하지 않습니다.
        connection.prepareStatement(
            """SELECT fingerprint,status,descriptor,revision,
                    COALESCE(lease_until>statement_timestamp() AND command_deadline>statement_timestamp(),false) AS active,
                    GREATEST(0,COALESCE(EXTRACT(EPOCH FROM (lease_until-statement_timestamp()))*1000,0))::bigint AS retry_millis
                FROM voucher_pool_http_idempotency
                WHERE tenant_id=? AND operation=? AND scoped_key_digest=? FOR UPDATE""".trimIndent(),
        ).use { statement ->
            statement.bindScope(scope, scopedKeyDigest)
            statement.executeQuery().use { result -> if (result.next()) result.idempotencyRow() else null }
        }

    /**
     * findTombstone은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param scopedKeyDigest 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun findTombstone(scope: CommandScope, scopedKeyDigest: ByteArray): TombstoneRow? =
        VoucherPoolCommandTombstoneTable
        .select(
            VoucherPoolCommandTombstoneTable.keyVersion,
            VoucherPoolCommandTombstoneTable.fingerprint,
            VoucherPoolCommandTombstoneTable.effectId,
            VoucherPoolCommandTombstoneTable.terminalCode,
        )
        .where {
            (VoucherPoolCommandTombstoneTable.tenantId eq scope.tenantId) and
                    (VoucherPoolCommandTombstoneTable.operation eq scope.operation) and
                    (VoucherPoolCommandTombstoneTable.scopedKeyDigest eq scopedKeyDigest)
        }
        .singleOrNull()
        ?.let {
            TombstoneRow(
                keyVersion = it[VoucherPoolCommandTombstoneTable.keyVersion],
                fingerprint = it[VoucherPoolCommandTombstoneTable.fingerprint],
                effectId = it[VoucherPoolCommandTombstoneTable.effectId],
                terminalCode = it[VoucherPoolCommandTombstoneTable.terminalCode]?.let(VoucherPoolErrorCode::valueOf),
            ).also(::requireCurrentTombstoneVersion)
        }

    /**
     * insertOrVerifyTombstone은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param owner 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param effect 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun insertOrVerifyTombstone(owner: IdempotencyOwner, effect: EffectReference) {
        VoucherPoolCommandTombstoneTable.insertIgnore {
            it[VoucherPoolCommandTombstoneTable.tenantId] = owner.scope.tenantId
            it[VoucherPoolCommandTombstoneTable.operation] = owner.scope.operation
            it[VoucherPoolCommandTombstoneTable.keyVersion] = owner.keyVersion
            it[VoucherPoolCommandTombstoneTable.scopedKeyDigest] = owner.copyScopedKeyDigest()
            it[VoucherPoolCommandTombstoneTable.fingerprint] = owner.fingerprint.copyBytes()
            it[VoucherPoolCommandTombstoneTable.effectId] = effect.effectId
            it[VoucherPoolCommandTombstoneTable.terminalCode] = effect.terminalCode?.name
        }
        val stored = checkNotNull(findTombstone(owner.scope, owner.copyScopedKeyDigest()))
        if (!MessageDigest.isEqual(stored.fingerprint, owner.fingerprint.copyBytes())) {
            tombstoneIntegrityFailure()
        }
        if (stored.effectId != effect.effectId || stored.terminalCode != effect.terminalCode) {
            tombstoneIntegrityFailure()
        }
    }

    /**
     * clearDescriptor은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param candidate 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun clearDescriptor(candidate: PurgeCandidate): Int =
        VoucherPoolHttpIdempotencyTable.update({
            (VoucherPoolHttpIdempotencyTable.tenantId eq candidate.scope.tenantId) and
                    (VoucherPoolHttpIdempotencyTable.operation eq candidate.scope.operation) and
                    (VoucherPoolHttpIdempotencyTable.scopedKeyDigest eq candidate.scopedKeyDigest) and
                    (VoucherPoolHttpIdempotencyTable.fingerprint eq candidate.fingerprint) and
                    (VoucherPoolHttpIdempotencyTable.status eq IdempotencyStatus.COMPLETED.name) and
                    VoucherPoolHttpIdempotencyTable.descriptor.isNotNull() and
                    (VoucherPoolHttpIdempotencyTable.expiresAt lessEq CurrentTimestamp)
        }) {
            it[VoucherPoolHttpIdempotencyTable.descriptor] = null
            it[VoucherPoolHttpIdempotencyTable.revision] = VoucherPoolHttpIdempotencyTable.revision + 1
        }

/**
 * completedDecision은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun IdempotencyRow.completedDecision(
        scope: CommandScope,
        scopedKeyDigest: ByteArray,
    ): IdempotencyDecision {
        val tombstone = findTombstone(scope, scopedKeyDigest) ?: tombstoneIntegrityFailure()
        if (!MessageDigest.isEqual(tombstone.fingerprint, fingerprint.copyBytes())) {
            tombstoneIntegrityFailure()
        }
        return descriptor
            ?.also {
                if (it.effectId != tombstone.effectId || it.terminalCode != tombstone.terminalCode) {
                    tombstoneIntegrityFailure()
                }
            }
            ?.let(IdempotencyDecision::Replay)
            ?: tombstone.decision(fingerprint)
    }

/**
 * decision은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun TombstoneRow.decision(fingerprint: CommandFingerprint): IdempotencyDecision =
        if (MessageDigest.isEqual(this.fingerprint, fingerprint.copyBytes())) {
            IdempotencyDecision.Expired(effectId, terminalCode)
        } else {
            IdempotencyDecision.FingerprintConflict
        }

/**
 * matches은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun CommandFingerprint.matches(other: CommandFingerprint): Boolean =
        MessageDigest.isEqual(copyBytes(), other.copyBytes())

    /**
     * newOwner은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun newOwner(
        scope: CommandScope,
        keyDigest: VoucherDigest,
        fingerprint: CommandFingerprint,
    ): IdempotencyOwner {
        val tokenBytes = ByteArray(OWNER_TOKEN_BYTES).also(random::nextBytes)
        return try {
            IdempotencyOwner(
                scope = scope,
                scopedKeyDigest = keyDigest.copyBytes(),
                keyVersion = keyDigest.keyVersion,
                fingerprint = fingerprint,
                ownerTokenDigest = sha256(OWNER_TOKEN_DOMAIN, tokenBytes),
            )
        } finally {
            tokenBytes.fill(0)
        }
    }

    /**
     * currentConnection은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun currentConnection(): Connection =
        TransactionManager.currentOrNull()
            .checkNotNull { "voucher idempotency repository requires an active VoucherPoolJdbcExecutor transaction" }
            .connection
            .connection as Connection

/**
 * bindScope은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun IdempotencyOwner.bindScope(statement: PreparedStatement, start: Int) {
        statement.setString(start, scope.tenantId)
        statement.setString(start + 1, scope.operation)
        statement.setBytes(start + 2, copyScopedKeyDigest())
    }

/**
 * bindScope은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun PreparedStatement.bindScope(scope: CommandScope, scopedKeyDigest: ByteArray) {
        setString(1, scope.tenantId)
        setString(2, scope.operation)
        setBytes(3, scopedKeyDigest)
    }

/**
 * idempotencyRow은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun ResultSet.idempotencyRow(): IdempotencyRow =
        IdempotencyRow(
            fingerprint = CommandFingerprint.of(getBytes("fingerprint")),
            status = IdempotencyStatus.valueOf(getString("status")),
            descriptor = getString("descriptor")?.toDescriptor(),
            revision = getLong("revision"),
            active = getBoolean("active"),
            retryAfterMillis = getLong("retry_millis"),
        )

/**
 * toJson은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun SafeResponseDescriptor.toJson(): String =
        Jackson.defaultJsonMapper.createObjectNode()
            .apply {
                put("status", status)
                outcome?.let { put("outcome", it) }
                effectId?.let { put("effectId", it.toString()) }
                revision?.let { put("revision", it) }
                terminalCode?.let { put("terminalCode", it.name) }
            }
            .toString()

/**
 * toDescriptor은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun String.toDescriptor(): SafeResponseDescriptor {
        try {
            val node = Jackson.defaultJsonMapper.readTree(this)
            if (!node.isObject) descriptorCorrupted()
            val fieldNames: Set<String> = node.propertyNames().toSet()
            val statusNode = node.get("status")
            if (statusNode == null || !statusNode.isIntegralNumber || !statusNode.canConvertToInt()) {
                descriptorCorrupted()
            }

            return when (fieldNames) {
                SUCCESS_DESCRIPTOR_FIELDS  -> {
                    val outcomeNode = node.get("outcome")
                    val effectIdNode = node.get("effectId")
                    val revisionNode = node.get("revision")
                    if (!outcomeNode.isString || !effectIdNode.isString) descriptorCorrupted()
                    if (!revisionNode.isIntegralNumber || !revisionNode.canConvertToLong()) descriptorCorrupted()

                    SafeResponseDescriptor.restore(
                        status = statusNode.intValue(),
                        outcome = outcomeNode.stringValue(),
                        effectId = UUID.fromString(effectIdNode.stringValue()),
                        revision = revisionNode.longValue(),
                        terminalCode = null,
                    )
                }
                TERMINAL_DESCRIPTOR_FIELDS -> {
                    val terminalCodeNode = node.get("terminalCode")
                    if (!terminalCodeNode.isString) descriptorCorrupted()

                    SafeResponseDescriptor.restore(
                        status = statusNode.intValue(),
                        outcome = null,
                        effectId = null,
                        revision = null,
                        terminalCode = VoucherPoolErrorCode.valueOf(terminalCodeNode.stringValue()),
                    )
                }
                else                       -> descriptorCorrupted()
            }
        } catch (_: Exception) {
            descriptorCorrupted()
        }
    }

    /**
     * requireDescriptorMatchesEffect은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을
     * 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param descriptor 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param effect 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun requireDescriptorMatchesEffect(descriptor: SafeResponseDescriptor, effect: EffectReference) {
        require(descriptor.effectId == effect.effectId && descriptor.terminalCode == effect.terminalCode) {
            "safe descriptor and tenant-lifetime effect reference must match"
        }
    }

    /**
     * IdempotencyRow은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private class IdempotencyRow(
        /**
         * `fingerprint`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val fingerprint: CommandFingerprint,
        /**
         * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val status: IdempotencyStatus,
        /**
         * `descriptor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val descriptor: SafeResponseDescriptor?,
        /**
         * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val revision: Long,
        /**
         * `active`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val active: Boolean,
        /**
         * `retryAfterMillis`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val retryAfterMillis: Long,
    )

    /**
     * TombstoneRow은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private class TombstoneRow(
        /**
         * `keyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val keyVersion: Int,
        /**
         * `fingerprint`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val fingerprint: ByteArray,
        /**
         * `effectId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val effectId: UUID?,
        /**
         * `terminalCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val terminalCode: VoucherPoolErrorCode?,
    )

    /**
     * PurgeCandidate은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private class PurgeCandidate(
        /**
         * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val tenantId: String,
        /**
         * `operation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val operation: String,
        /**
         * `scopedKeyDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val scopedKeyDigest: ByteArray,
        /**
         * `fingerprint`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val fingerprint: ByteArray,
        /**
         * `tombstone`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val tombstone: TombstoneRow,
    ) {
        /**
         * `scope`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val scope: CommandScope get() = CommandScope(tenantId, operation)
    }

    /**
     * IdempotencyStatus은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private enum class IdempotencyStatus {
        /**
         * `IdempotencyStatus.OWNED`은 IdempotencyStatus 업무 상태의 `OWNED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
         * 분기를 선택하는
         * 기준으로 사용합니다.
         */
        OWNED,
        /**
         * `IdempotencyStatus.COMPLETED`은 IdempotencyStatus 업무 상태의 `COMPLETED` 단계입니다. 허용된 상태 전이와
         * 재시도·보상·응답 분기를
         * 선택하는 기준으로 사용합니다.
         */
        COMPLETED,
        /**
         * `IdempotencyStatus.RETRYABLE_FAILED`은 IdempotencyStatus 업무 상태의 `RETRYABLE_FAILED` 단계입니다.
         * 허용된 상태 전이와
         * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
         */
        RETRYABLE_FAILED
    }

    /**
     * requireCurrentTombstoneVersion은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을
     * 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tombstone 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun requireCurrentTombstoneVersion(tombstone: TombstoneRow) {
        if (tombstone.keyVersion != digests.commandTombstoneKeyVersion) {
            throw IdempotencyTombstoneKeyVersionException()
        }
    }

    /**
     * ownershipLost은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun ownershipLost(): Nothing = throw IdempotencyOwnershipLostException()

    /**
     * tombstoneIntegrityFailure은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun tombstoneIntegrityFailure(): Nothing = throw IdempotencyTombstoneIntegrityException()

    /**
     * descriptorCorrupted은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun descriptorCorrupted(): Nothing = throw IdempotencyDescriptorCorruptedException()
}

/**
 * requireDuration은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param duration 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 * @param name 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun requireDuration(duration: Duration, name: String) {
    duration.requirePositiveDuration(name)
    duration.toMillis().requirePositiveNumber { "$name must be at least one millisecond" }
}

/**
 * sha256은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param domain 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun sha256(domain: String, value: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
        update(domain.toUtf8Bytes())
        update(0)
        digest(value)
    }

/**
 * `OWNER_TOKEN_DOMAIN`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
 *
 * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
 */
private const val OWNER_TOKEN_DOMAIN = "voucher-pool-idempotency-owner-v1"
/**
 * `OWNER_DIGEST_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val OWNER_DIGEST_BYTES = 32
