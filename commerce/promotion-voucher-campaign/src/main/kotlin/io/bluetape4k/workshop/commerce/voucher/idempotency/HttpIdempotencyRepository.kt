package io.bluetape4k.workshop.commerce.voucher.idempotency

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * IdempotencyStatus은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class IdempotencyStatus {
    /**
     * `IdempotencyStatus.IN_PROGRESS`은 IdempotencyStatus 업무 상태의 `IN_PROGRESS` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답
     * 분기를 선택하는 기준으로 사용합니다.
     */
    IN_PROGRESS,
    /**
     * `IdempotencyStatus.SUCCEEDED`은 IdempotencyStatus 업무 상태의 `SUCCEEDED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    SUCCEEDED,
    /**
     * `IdempotencyStatus.FAILED`은 IdempotencyStatus 업무 상태의 `FAILED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
     * 분기를 선택하는
     * 기준으로 사용합니다.
     */
    FAILED,
}

/** 임의 serialized body 대신 닫힌 response variant를 저장합니다. */
/** 임의 serialized body 대신 닫힌 response variant를 저장합니다. */
internal enum class VoucherResponseKind {
    /**
     * `VoucherResponseKind.CAMPAIGN_CREATED`은 VoucherResponseKind 업무 상태의 `CAMPAIGN_CREATED` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_CREATED,
    /**
     * `VoucherResponseKind.CAMPAIGN_ACTIVATED`은 VoucherResponseKind 업무 상태의 `CAMPAIGN_ACTIVATED`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_ACTIVATED,
    /**
     * `VoucherResponseKind.CAMPAIGN_PAUSE_ACCEPTED`은 VoucherResponseKind 업무 상태의
     * `CAMPAIGN_PAUSE_ACCEPTED`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_PAUSE_ACCEPTED,
    /**
     * `VoucherResponseKind.CAMPAIGN_END_ACCEPTED`은 VoucherResponseKind 업무 상태의
     * `CAMPAIGN_END_ACCEPTED`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_END_ACCEPTED,
    /**
     * `VoucherResponseKind.CAMPAIGN_POLICY_UPDATED`은 VoucherResponseKind 업무 상태의
     * `CAMPAIGN_POLICY_UPDATED`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_POLICY_UPDATED,
    /**
     * `VoucherResponseKind.ALLOCATION_ACCEPTED`은 VoucherResponseKind 업무 상태의 `ALLOCATION_ACCEPTED`
     * 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    ALLOCATION_ACCEPTED,
    /**
     * `VoucherResponseKind.ALLOCATION_REVIEW_REQUIRED`은 VoucherResponseKind 업무 상태의
     * `ALLOCATION_REVIEW_REQUIRED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    ALLOCATION_REVIEW_REQUIRED,
    /**
     * `VoucherResponseKind.REDEMPTION_ACCEPTED`은 VoucherResponseKind 업무 상태의 `REDEMPTION_ACCEPTED`
     * 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    REDEMPTION_ACCEPTED,
    /**
     * `VoucherResponseKind.REDEMPTION_REVIEW_REQUIRED`은 VoucherResponseKind 업무 상태의
     * `REDEMPTION_REVIEW_REQUIRED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    REDEMPTION_REVIEW_REQUIRED,
    /**
     * `VoucherResponseKind.CLAIM_RELEASED`은 VoucherResponseKind 업무 상태의 `CLAIM_RELEASED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CLAIM_RELEASED,
    /**
     * `VoucherResponseKind.CLAIM_REVOCATION_ACCEPTED`은 VoucherResponseKind 업무 상태의
     * `CLAIM_REVOCATION_ACCEPTED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CLAIM_REVOCATION_ACCEPTED,
    /**
     * `VoucherResponseKind.CODE_ACKNOWLEDGED`은 VoucherResponseKind 업무 상태의 `CODE_ACKNOWLEDGED`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CODE_ACKNOWLEDGED,
    /**
     * `VoucherResponseKind.REVIEW_APPROVED`은 VoucherResponseKind 업무 상태의 `REVIEW_APPROVED` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    REVIEW_APPROVED,
    /**
     * `VoucherResponseKind.REVIEW_REJECTED`은 VoucherResponseKind 업무 상태의 `REVIEW_REJECTED` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    REVIEW_REJECTED,
    /**
     * `VoucherResponseKind.FIXTURE_CONFIGURED`은 VoucherResponseKind 업무 상태의 `FIXTURE_CONFIGURED`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    FIXTURE_CONFIGURED,
    /**
     * `VoucherResponseKind.RECONCILIATION_COMPLETED`은 VoucherResponseKind 업무 상태의
     * `RECONCILIATION_COMPLETED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    RECONCILIATION_COMPLETED,
    /**
     * `VoucherResponseKind.RECONCILIATION_IN_PROGRESS`은 VoucherResponseKind 업무 상태의
     * `RECONCILIATION_IN_PROGRESS` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    RECONCILIATION_IN_PROGRESS,
    /**
     * `VoucherResponseKind.RATE_LIMITED`은 VoucherResponseKind 업무 상태의 `RATE_LIMITED` 단계입니다. 허용된 상태
     * 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    RATE_LIMITED,
    /**
     * `VoucherResponseKind.DATABASE_BULKHEAD_REJECTED`은 VoucherResponseKind 업무 상태의
     * `DATABASE_BULKHEAD_REJECTED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    DATABASE_BULKHEAD_REJECTED,
    /**
     * `VoucherResponseKind.AUTHORITATIVE_BACKEND_UNAVAILABLE`은 VoucherResponseKind 업무 상태의
     * `AUTHORITATIVE_BACKEND_UNAVAILABLE` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    AUTHORITATIVE_BACKEND_UNAVAILABLE,
    /**
     * `VoucherResponseKind.CAMPAIGN_PAUSED`은 VoucherResponseKind 업무 상태의 `CAMPAIGN_PAUSED` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_PAUSED,
    /**
     * `VoucherResponseKind.CAMPAIGN_ENDED`은 VoucherResponseKind 업무 상태의 `CAMPAIGN_ENDED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_ENDED,
    /**
     * `VoucherResponseKind.CLAIM_EXPIRED`은 VoucherResponseKind 업무 상태의 `CLAIM_EXPIRED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CLAIM_EXPIRED,
    /**
     * `VoucherResponseKind.CLAIM_REVOKED`은 VoucherResponseKind 업무 상태의 `CLAIM_REVOKED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CLAIM_REVOKED,
    /**
     * `VoucherResponseKind.ALREADY_REDEEMED`은 VoucherResponseKind 업무 상태의 `ALREADY_REDEEMED` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    ALREADY_REDEEMED,
    /**
     * `VoucherResponseKind.CAPACITY_EXHAUSTED`은 VoucherResponseKind 업무 상태의 `CAPACITY_EXHAUSTED`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAPACITY_EXHAUSTED,
    /**
     * `VoucherResponseKind.PER_USER_LIMIT_REACHED`은 VoucherResponseKind 업무 상태의
     * `PER_USER_LIMIT_REACHED`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    PER_USER_LIMIT_REACHED,
    /**
     * `VoucherResponseKind.STALE_REVISION`은 VoucherResponseKind 업무 상태의 `STALE_REVISION` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    STALE_REVISION,
    /**
     * `VoucherResponseKind.CAMPAIGN_ALREADY_EXISTS`은 VoucherResponseKind 업무 상태의
     * `CAMPAIGN_ALREADY_EXISTS`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_ALREADY_EXISTS,
    /**
     * `VoucherResponseKind.CAMPAIGN_NOT_FOUND`은 VoucherResponseKind 업무 상태의 `CAMPAIGN_NOT_FOUND`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_NOT_FOUND,
    /**
     * `VoucherResponseKind.CLAIM_NOT_FOUND`은 VoucherResponseKind 업무 상태의 `CLAIM_NOT_FOUND` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CLAIM_NOT_FOUND,
    /**
     * `VoucherResponseKind.REVIEW_NOT_FOUND`은 VoucherResponseKind 업무 상태의 `REVIEW_NOT_FOUND` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    REVIEW_NOT_FOUND,
    /**
     * `VoucherResponseKind.CAMPAIGN_NOT_ACTIVE`은 VoucherResponseKind 업무 상태의 `CAMPAIGN_NOT_ACTIVE`
     * 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_NOT_ACTIVE,
    /**
     * `VoucherResponseKind.CAMPAIGN_NOT_STARTED`은 VoucherResponseKind 업무 상태의
     * `CAMPAIGN_NOT_STARTED` 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_NOT_STARTED,
    /**
     * `VoucherResponseKind.INVALID_CODE`은 VoucherResponseKind 업무 상태의 `INVALID_CODE` 단계입니다. 허용된 상태
     * 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    INVALID_CODE,
    /**
     * `VoucherResponseKind.CONCURRENT_MODIFICATION`은 VoucherResponseKind 업무 상태의
     * `CONCURRENT_MODIFICATION`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CONCURRENT_MODIFICATION,
    /**
     * `VoucherResponseKind.CODE_ALREADY_ACKNOWLEDGED`은 VoucherResponseKind 업무 상태의
     * `CODE_ALREADY_ACKNOWLEDGED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CODE_ALREADY_ACKNOWLEDGED,
    /**
     * `VoucherResponseKind.IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE`은 VoucherResponseKind 업무 상태의
     * `IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE,
}

/**
 * StoredHttpResponse은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class StoredHttpResponse(
    /**
     * `responseKind`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val responseKind: VoucherResponseKind,
    /**
     * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: Int,
    /**
     * `headers`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val headers: Map<String, String>,
    /**
     * `aggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId: UUID,
    /**
     * `allocationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val allocationId: UUID?,
    /**
     * `aggregateRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val aggregateRevision: Long,
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
    init {
        require(status in 100..599) { "response status must be a valid HTTP status" }
        require(aggregateRevision >= 0) { "aggregateRevision must not be negative" }
        require(headers.size <= MAX_HEADERS) { "too many stored response headers" }
        require(headers.keys.all { it in ALLOWED_RESPONSE_HEADERS }) { "unsupported stored response header" }
        require((generationKeyVersion == null) == (verificationKeyVersion == null)) {
            "generation and verification key versions must be stored together"
        }
    }

    companion object {
        /**
         * `MAX_HEADERS`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        private const val MAX_HEADERS = 4
        /**
         * `ALLOWED_RESPONSE_HEADERS`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
         *
         * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
         */
        private val ALLOWED_RESPONSE_HEADERS = setOf(
            "Content-Type",
            "ETag",
            "Location",
            "Retry-After",
            "X-Workshop-Campaign-Descriptor",
            "X-Workshop-Claim-Descriptor",
            "X-Workshop-Allocation-Descriptor",
            "X-Workshop-Reconciliation-Result",
            "X-Workshop-Fixture-Evidence",
            "X-Workshop-Fixture-Reset-Result",
        )
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * IdempotencyScope은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class IdempotencyScope(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `principalDigest`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val principalDigest: Digest,
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
    val keyDigest: Digest,
): Serializable {
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
 * IdempotencyRecord은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class IdempotencyRecord(
    /**
     * `id`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `scope`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scope: IdempotencyScope,
    /**
     * `requestFingerprint`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val requestFingerprint: Digest,
    /**
     * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: IdempotencyStatus,
    /**
     * `ownerTokenDigest`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerTokenDigest: Digest?,
    /**
     * `leaseUntil`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val leaseUntil: Instant?,
    /**
     * `commandDeadline`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val commandDeadline: Instant,
    /**
     * `response`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val response: StoredHttpResponse?,
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant,
    /**
     * `createdBy`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    /**
     * `createdAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val createdAt: Instant? = null,
    /**
     * `updatedBy`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val updatedBy: String? = null,
    /**
     * `updatedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val updatedAt: Instant? = null,
): Auditable,
   Serializable {
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
 * IdempotencyAcquireResult은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal sealed interface IdempotencyAcquireResult: Serializable {
    /**
     * Owner은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Owner(
        /**
         * `ownerToken`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val ownerToken: OwnerToken,
        /**
         * `leaseUntil`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val leaseUntil: Instant,
    ): IdempotencyAcquireResult

    /**
     * Replay은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Replay(
        /**
         * `response`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val response: StoredHttpResponse,
    ): IdempotencyAcquireResult

    /**
     * InProgress은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class InProgress(
        /**
         * `retryAfter`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val retryAfter: Duration,
    ): IdempotencyAcquireResult

    /**
     * FingerprintConflict은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data object FingerprintConflict: IdempotencyAcquireResult {
        /**
         * readResolve은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        private fun readResolve(): Any = FingerprintConflict
    }
}

/**
 * HttpIdempotencyTable은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal object HttpIdempotencyTable: AuditableLongIdTable("voucher_http_idempotency") {
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId = varchar("tenant_id", 64)
    /**
     * `principalDigest`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val principalDigest = char("principal_digest", 43)
    /**
     * `operation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val operation = varchar("operation", 64)
    /**
     * `resourceId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId = varchar("resource_id", 128)
    /**
     * `keyDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val keyDigest = char("key_digest", 43)
    /**
     * `requestFingerprint`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val requestFingerprint = char("request_fingerprint", 43)
    /**
     * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status = enumerationByName<IdempotencyStatus>("status", 24)
    /**
     * `ownerTokenDigest`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerTokenDigest = char("owner_token_digest", 43).nullable()
    /**
     * `leaseUntil`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val leaseUntil = timestamp("lease_until").nullable()
    /**
     * `commandDeadline`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val commandDeadline = timestamp("command_deadline")
    /**
     * `responseKind`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val responseKind = enumerationByName<VoucherResponseKind>("response_kind", 48).nullable()
    /**
     * `responseStatus`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val responseStatus = integer("response_status").nullable()
    /**
     * `responseHeaders`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val responseHeaders = varchar("response_headers", 512).nullable()
    /**
     * `aggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId = javaUUID("aggregate_id").nullable()
    /**
     * `allocationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val allocationId = javaUUID("allocation_id").nullable()
    /**
     * `aggregateRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val aggregateRevision = long("aggregate_revision").nullable()
    /**
     * `generationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generationKeyVersion = integer("generation_key_version").nullable()
    /**
     * `verificationKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val verificationKeyVersion = integer("verification_key_version").nullable()
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt = timestamp("expires_at")

    init {
        uniqueIndex(tenantId, principalDigest, operation, resourceId, keyDigest)
        index(false, status, expiresAt, id)
    }
}

/**
 * VoucherIdempotencyStore은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface VoucherIdempotencyStore {
    /** terminal replay/conflict state만 반환합니다. in-progress row는 [acquire]가 처리합니다. */
    /** terminal replay/conflict state만 반환합니다. in-progress row는 [acquire]가 처리합니다. */
    fun lookup(
        scope: IdempotencyScope,
        fingerprint: Digest,
    ): IdempotencyAcquireResult?

    /**
     * acquire은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun acquire(
        scope: IdempotencyScope,
        fingerprint: Digest,
        now: Instant,
        ownerToken: OwnerToken = OwnerToken.random(),
        lease: Duration = HttpIdempotencyRepository.DEFAULT_LEASE,
        commandTimeout: Duration = HttpIdempotencyRepository.DEFAULT_COMMAND_TIMEOUT,
        retention: Duration = HttpIdempotencyRepository.DEFAULT_RETENTION,
    ): IdempotencyAcquireResult

    /**
     * isOwner은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun isOwner(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
        now: Instant,
    ): Boolean

    /**
     * finalize은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun finalize(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
        now: Instant,
        response: StoredHttpResponse,
    ): Boolean

    /**
     * release은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun release(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
    ): Boolean

    /**
     * find은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun find(scope: IdempotencyScope): IdempotencyRecord?

    /**
     * cleanupExpired은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun cleanupExpired(
        now: Instant,
        limit: Int,
    ): Int
}

/** scoped HTTP idempotency, lease takeover, terminal replay를 담당하는 PostgreSQL authority입니다. */
/** scoped HTTP idempotency, lease takeover, terminal replay를 담당하는 PostgreSQL authority입니다. */
@Repository
internal class HttpIdempotencyRepository(
    /**
     * `gate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val gate: DatabasePermitGate,
): LongAuditableJdbcRepository<IdempotencyRecord, HttpIdempotencyTable>,
   VoucherIdempotencyStore {
    /**
     * `table`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    override val table = HttpIdempotencyTable

    /**
     * extractId은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param entity 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun extractId(entity: IdempotencyRecord): Long = entity.id

/**
 * toEntity은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    override fun ResultRow.toEntity(): IdempotencyRecord {
        val persistedStatus = this[table.status]
        val response =
            if (persistedStatus == IdempotencyStatus.IN_PROGRESS) {
                null
            } else {
                StoredHttpResponse(
                    responseKind = requireNotNull(this[table.responseKind]),
                    status = requireNotNull(this[table.responseStatus]),
                    headers = decodeHeaders(requireNotNull(this[table.responseHeaders])),
                    aggregateId = requireNotNull(this[table.aggregateId]),
                    allocationId = this[table.allocationId],
                    aggregateRevision = requireNotNull(this[table.aggregateRevision]),
                    generationKeyVersion = this[table.generationKeyVersion],
                    verificationKeyVersion = this[table.verificationKeyVersion],
                )
            }
        return IdempotencyRecord(
            id = this[table.id].value,
            scope = IdempotencyScope(
                tenantId = this[table.tenantId],
                principalDigest = Digest.of(decodeBase64Url(this[table.principalDigest])),
                operation = this[table.operation],
                resourceId = this[table.resourceId],
                keyDigest = Digest.of(decodeBase64Url(this[table.keyDigest])),
            ),
            requestFingerprint = Digest.of(decodeBase64Url(this[table.requestFingerprint])),
            status = persistedStatus,
            ownerTokenDigest = this[table.ownerTokenDigest]?.let { Digest.of(decodeBase64Url(it)) },
            leaseUntil = this[table.leaseUntil],
            commandDeadline = this[table.commandDeadline],
            response = response,
            expiresAt = this[table.expiresAt],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt],
        )
    }

    /**
     * lookup은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun lookup(
        scope: IdempotencyScope,
        fingerprint: Digest,
    ): IdempotencyAcquireResult? {
        gate.requireHeld()
        val current = find(scope) ?: return null
        if (current.requestFingerprint != fingerprint) {
            return IdempotencyAcquireResult.FingerprintConflict
        }
        return current.response?.let(IdempotencyAcquireResult::Replay)
    }

    /**
     * acquire은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun acquire(
        scope: IdempotencyScope,
        fingerprint: Digest,
        now: Instant,
        ownerToken: OwnerToken,
        lease: Duration,
        commandTimeout: Duration,
        retention: Duration,
    ): IdempotencyAcquireResult {
        gate.requireHeld()
        validate(scope, lease, commandTimeout, retention)
        val ownerDigest = ownerToken.digest()
        val leaseUntil = now.plus(lease)
        val inserted = table.insertIgnore {
            it[tenantId] = scope.tenantId
            it[principalDigest] = scope.principalDigest.base64Url
            it[operation] = scope.operation
            it[resourceId] = scope.resourceId
            it[keyDigest] = scope.keyDigest.base64Url
            it[requestFingerprint] = fingerprint.base64Url
            it[status] = IdempotencyStatus.IN_PROGRESS
            it[ownerTokenDigest] = ownerDigest.base64Url
            it[HttpIdempotencyTable.leaseUntil] = leaseUntil
            it[commandDeadline] = now.plus(commandTimeout)
            it[expiresAt] = now.plus(retention)
        }.insertedCount == 1

        var current = find(scope) ?: error("idempotency row was not persisted")
        if (inserted) {
            log.debug { "voucher_idempotency_acquired scope=${scope.logScope()}" }
            return IdempotencyAcquireResult.Owner(ownerToken, leaseUntil)
        }
        if (current.requestFingerprint != fingerprint) {
            log.warn { "voucher_idempotency_fingerprint_conflict scope=${scope.logScope()}" }
            return IdempotencyAcquireResult.FingerprintConflict
        }
        current.response?.let { return IdempotencyAcquireResult.Replay(it) }
        val currentLease = current.leaseUntil.requireNotNull("current.leaseUntil")
        if (currentLease.isAfter(now)) {
            return IdempotencyAcquireResult.InProgress(Duration.between(now, currentLease))
        }

        val previousOwner = requireNotNull(current.ownerTokenDigest)
        val reclaimed = auditedUpdateAll(
            predicate = {
                (table.id eq current.id) and
                        (table.status eq IdempotencyStatus.IN_PROGRESS) and
                        (table.ownerTokenDigest eq previousOwner.base64Url) and
                        (table.leaseUntil eq currentLease)
            },
        ) {
            it[ownerTokenDigest] = ownerDigest.base64Url
            it[HttpIdempotencyTable.leaseUntil] = leaseUntil
            it[commandDeadline] = now.plus(commandTimeout)
            it[expiresAt] = now.plus(retention)
        } == 1

        if (reclaimed) {
            log.warn { "voucher_idempotency_lease_taken_over scope=${scope.logScope()}" }
            return IdempotencyAcquireResult.Owner(ownerToken, leaseUntil)
        }

        current = find(scope) ?: error("idempotency row disappeared")
        current.response?.let { return IdempotencyAcquireResult.Replay(it) }

        return IdempotencyAcquireResult.InProgress(
            Duration.between(now, requireNotNull(current.leaseUntil)).coerceAtLeast(Duration.ZERO),
        )
    }

    /**
     * isOwner은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun isOwner(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
        now: Instant,
    ): Boolean {
        gate.requireHeld()
        val ownerDigest = ownerToken.digest().base64Url
        return table
            .selectAll()
            .where {
                scopePredicate(scope) and
                        (table.status eq IdempotencyStatus.IN_PROGRESS) and
                        (table.ownerTokenDigest eq ownerDigest) and
                        (table.leaseUntil greaterEq now) and
                        (table.commandDeadline greaterEq now)
            }
            .limit(1)
            .any()
    }

    /**
     * finalize은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun finalize(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
        now: Instant,
        response: StoredHttpResponse,
    ): Boolean {
        gate.requireHeld()
        val encodedHeaders = encodeHeaders(response.headers)
        require(encodedHeaders.length <= MAX_RESPONSE_HEADERS) { "stored response headers are too large" }

        val finalized = auditedUpdateAll(
            predicate = {
                scopePredicate(scope) and
                        (table.status eq IdempotencyStatus.IN_PROGRESS) and
                        (table.ownerTokenDigest eq ownerToken.digest().base64Url) and
                        (table.leaseUntil greaterEq now) and
                        (table.commandDeadline greaterEq now)
            },
        ) {
            it[status] =
                if (response.status < 400) IdempotencyStatus.SUCCEEDED else IdempotencyStatus.FAILED
            it[responseKind] = response.responseKind
            it[responseStatus] = response.status
            it[responseHeaders] = encodedHeaders
            it[aggregateId] = response.aggregateId
            it[allocationId] = response.allocationId
            it[aggregateRevision] = response.aggregateRevision
            it[generationKeyVersion] = response.generationKeyVersion
            it[verificationKeyVersion] = response.verificationKeyVersion
            it[ownerTokenDigest] = null
            it[leaseUntil] = null
        } == 1

        log.debug { "voucher_idempotency_finalized scope=${scope.logScope()} finalized=$finalized" }
        return finalized
    }

    /**
     * release은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun release(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
    ): Boolean {
        gate.requireHeld()
        val released = table.deleteWhere {
            scopePredicate(scope) and
                    (table.status eq IdempotencyStatus.IN_PROGRESS) and
                    (table.ownerTokenDigest eq ownerToken.digest().base64Url)
        } == 1
        log.debug { "voucher_idempotency_owner_released scope=${scope.logScope()} released=$released" }
        return released
    }

    /**
     * find은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun find(scope: IdempotencyScope): IdempotencyRecord? {
        gate.requireHeld()
        return scopedQuery(scope).singleOrNull()?.let { with(this) { it.toEntity() } }
    }

    /**
     * cleanupExpired은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun cleanupExpired(
        now: Instant,
        limit: Int,
    ): Int {
        gate.requireHeld()
        require(limit in 1..MAX_CLEANUP_BATCH) { "cleanup limit must be between 1 and $MAX_CLEANUP_BATCH" }

        val ids = table
            .select(table.id)
            .where { (table.status neq IdempotencyStatus.IN_PROGRESS) and (table.expiresAt lessEq now) }
            .orderBy(table.id)
            .limit(limit)
            .map { it[table.id] }

        return if (ids.isEmpty()) 0 else table.deleteWhere { table.id inList ids }
    }

    /**
     * scopedQuery은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun scopedQuery(scope: IdempotencyScope) =
        table.selectAll().where { scopePredicate(scope) }

    /**
     * scopePredicate은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun scopePredicate(scope: IdempotencyScope) =
        (table.tenantId eq scope.tenantId) and
                (table.principalDigest eq scope.principalDigest.base64Url) and
                (table.operation eq scope.operation) and
                (table.resourceId eq scope.resourceId) and
                (table.keyDigest eq scope.keyDigest.base64Url)

    /**
     * validate은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun validate(
        scope: IdempotencyScope,
        lease: Duration,
        commandTimeout: Duration,
        retention: Duration,
    ) {
        require(scope.tenantId.length in 1..64) { "tenantId must contain 1..64 characters" }
        require(scope.operation.length in 1..64) { "operation must contain 1..64 characters" }
        require(scope.resourceId.length in 1..128) { "resourceId must contain 1..128 characters" }
        require(commandTimeout > Duration.ZERO && commandTimeout < lease) { "command timeout must be shorter than lease" }
        require(retention > lease) { "retention must outlive lease" }
    }

/**
 * logScope은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun IdempotencyScope.logScope(): String =
        "operation=$operation resourceId=$resourceId keyDigestPrefix=${keyDigest.base64Url.take(LOG_DIGEST_PREFIX)}"

    companion object: KLogging() {
        val DEFAULT_LEASE: Duration = 90.seconds()
        val DEFAULT_COMMAND_TIMEOUT: Duration = 60.seconds()
        val DEFAULT_RETENTION: Duration = 24.hours()
        /**
         * `MAX_RESPONSE_HEADERS`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        private const val MAX_RESPONSE_HEADERS = 512
        /**
         * `MAX_CLEANUP_BATCH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private const val MAX_CLEANUP_BATCH = 1_000
        /**
         * `LOG_DIGEST_PREFIX`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        private const val LOG_DIGEST_PREFIX = 12
    }
}

/**
 * encodeHeaders은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param headers 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun encodeHeaders(headers: Map<String, String>): String =
    headers.toSortedMap().entries.joinToString("&") { (name, value) ->
        "${urlEncode(name)}=${urlEncode(value)}"
    }

/**
 * decodeHeaders은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param encoded 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun decodeHeaders(encoded: String): Map<String, String> =
    if (encoded.isEmpty()) {
        emptyMap()
    } else {
        encoded.split('&').associate { pair ->
            /**
             * `separator`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val separator = pair.indexOf('=')
            require(separator > 0) { "invalid stored response header descriptor" }
            urlDecode(pair.substring(0, separator)) to urlDecode(pair.substring(separator + 1))
        }
    }

/**
 * urlEncode은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun urlEncode(value: String): String =
    buildString {
        value.toUtf8Bytes().forEach { byte ->
            /**
             * `unsigned`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val unsigned = byte.toInt() and 0xff
            if (
                unsigned in 'a'.code..'z'.code ||
                unsigned in 'A'.code..'Z'.code ||
                unsigned in '0'.code..'9'.code ||
                unsigned == '-'.code ||
                unsigned == '.'.code ||
                unsigned == '_'.code ||
                unsigned == '~'.code
            ) {
                append(unsigned.toChar())
            } else {
                append('%').append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0f])
            }
        }
    }

/**
 * urlDecode은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun urlDecode(value: String): String = URLDecoder.decode(value, UTF_8)

/**
 * decodeBase64Url은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun decodeBase64Url(value: String): ByteArray =
    java.util.Base64.getUrlDecoder().decode(value)

/**
 * `HEX`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val HEX = "0123456789ABCDEF"
