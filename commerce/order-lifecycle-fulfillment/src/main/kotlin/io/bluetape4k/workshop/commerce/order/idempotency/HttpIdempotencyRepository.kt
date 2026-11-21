package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.commerce.order.persistence.HttpIdempotencyTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * `IdempotencyStatus`은 상태 코드입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class IdempotencyStatus {
    /**
     * `IdempotencyStatus.IN_PROGRESS`은 외부 시스템 또는 비동기 worker가 처리를 진행 중인 값입니다.
     */
    IN_PROGRESS,
    /**
     * `IdempotencyStatus.SUCCEEDED`은 외부 provider 또는 내부 작업의 성공 결과가 확정된 값입니다.
     */
    SUCCEEDED,
    /**
     * `IdempotencyStatus.FAILED`은 처리가 실패했으며 재시도 가능 여부를 별도 정책으로 판단해야 하는 값입니다.
     */
    FAILED
}
/**
 * IdempotencyScope은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 주문·fulfillment 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게
 * 합니다.
 * @property operation 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property keyHash 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class IdempotencyScope(
    /**
     * `tenantId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `operation`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val operation: String,
    /**
     * `keyHash`은 주문·결제·재고·배송에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val keyHash: String,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * IdempotencyRecord은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property scope 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property requestFingerprint 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를
 * 확인합니다.
 * @property status 현재 주문·fulfillment 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property leaseUntil 주문·fulfillment의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property responseStatus 현재 주문·fulfillment 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의
 * 문자열로
 * 해석하지 않습니다.
 * @property responseBody 주문·fulfillment 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한
 * 정보만 포함합니다.
 * @property expiresAt 주문·fulfillment의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property createdBy 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property createdAt 주문·fulfillment의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property updatedBy 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property updatedAt 주문·fulfillment의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */


internal data class IdempotencyRecord(
    /**
     * `id`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long = 0,
    /**
     * `scope`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scope: IdempotencyScope,
    /**
     * `requestFingerprint`은 주문·결제·재고·배송에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val requestFingerprint: String,
    /**
     * `status`은 주문·결제·재고·배송에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: IdempotencyStatus,
    /**
     * `ownerToken`은 주문·결제·재고·배송에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerToken: UUID,
    /**
     * `leaseUntil`은 주문·결제·재고·배송에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val leaseUntil: Instant,
    /**
     * `responseStatus`은 주문·결제·재고·배송에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val responseStatus: Int? = null,
    /**
     * `responseBody`은 주문·결제·재고·배송에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val responseBody: String? = null,
    /**
     * `expiresAt`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant,
    /**
     * `createdBy`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    /**
     * `createdAt`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val createdAt: Instant? = null,
    /**
     * `updatedBy`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val updatedBy: String? = null,
    /**
     * `updatedAt`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val updatedAt: Instant? = null,
): Auditable,
   Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * AcquireResult은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface AcquireResult: Serializable {
/**
 * Acquired은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property record 주문·fulfillment 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 */


    data class Acquired(
        /**
         * `record`은 주문·결제·재고·배송에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val record: IdempotencyRecord,
    ): AcquireResult {
        companion object {
            /**
             * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
/**
 * Replay은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property status 현재 주문·fulfillment 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property body 주문·fulfillment 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property failed 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Replay(
        /**
         * `status`은 주문·결제·재고·배송에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val status: Int,
        /**
         * `body`은 주문·결제·재고·배송에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val body: String,
        /**
         * `failed`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val failed: Boolean,
    ): AcquireResult {
        companion object {
            /**
             * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * FingerprintConflict은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object FingerprintConflict: AcquireResult {
        /**
         * readResolve은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로
         * 현재 상태를 추정하지
         * 않습니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        private fun readResolve(): Any = FingerprintConflict
    }
/**
 * InProgress은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property retryAfter 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께
 * 적용합니다.
 */


    data class InProgress(
        /**
         * `retryAfter`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val retryAfter: Duration,
    ): AcquireResult {
        companion object {
            /**
             * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
}

@Repository
/**
 * HttpIdempotencyRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class HttpIdempotencyRepository: LongAuditableJdbcRepository<IdempotencyRecord, HttpIdempotencyTable> {

    companion object: KLogging() {
        /**
         * 주문·fulfillment 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
         */
        const val MAX_RESPONSE_BODY = 64 * 1024
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val MAX_CLEANUP_BATCH = 1_000
    }
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = HttpIdempotencyTable

    /**
     * extractId은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: IdempotencyRecord) = entity.id

    /**
     * toEntity은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity() =
        IdempotencyRecord(
            id = this[table.id].value,
            scope = IdempotencyScope(this[table.tenantId], this[table.operation], this[table.keyHash]),
            requestFingerprint = this[table.requestFingerprint],
            status = this[table.status],
            ownerToken = this[table.ownerToken],
            leaseUntil = this[table.leaseUntil],
            responseStatus = this[table.responseStatus],
            responseBody = this[table.responseBody],
            expiresAt = this[table.expiresAt],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    /**
     * acquire은 주문 라이프사이클과 fulfillment의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param fingerprint 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ownerToken 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param retention 업무 처리에 필요한 retention 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun acquire(
        scope: IdempotencyScope,
        fingerprint: String,
        ownerToken: UUID,
        now: Instant,
        lease: Duration,
        retention: Duration,
    ): AcquireResult {
        require(scope.tenantId.length in 1..80)
        require(scope.operation.length in 1..80)
        require(scope.keyHash.length == 64)
        require(fingerprint.length == 64)

        val inserted = table.insertIgnore {
            it[tenantId] = scope.tenantId
            it[operation] = scope.operation
            it[keyHash] = scope.keyHash
            it[requestFingerprint] = fingerprint
            it[status] = IdempotencyStatus.IN_PROGRESS
            it[HttpIdempotencyTable.ownerToken] = ownerToken
            it[leaseUntil] = now.plus(lease)
            it[expiresAt] = now.plus(retention)
        }.insertedCount == 1

        var current = findByScope(scope) ?: error("idempotency record was not persisted")
        if (inserted) {
            log.debug {
                "idempotency_record_acquired operation=${scope.operation} keyHashPrefix=${scope.keyHash.take(12)}"
            }
            return AcquireResult.Acquired(current)
        }
        if (current.requestFingerprint != fingerprint) {
            log.warn {
                "idempotency_record_conflict operation=${scope.operation} keyHashPrefix=${scope.keyHash.take(12)}"
            }
            return AcquireResult.FingerprintConflict
        }
        if (current.status != IdempotencyStatus.IN_PROGRESS) {
            return AcquireResult.Replay(
                status = requireNotNull(current.responseStatus),
                body = requireNotNull(current.responseBody),
                failed = current.status == IdempotencyStatus.FAILED
            )
        }
        if (current.leaseUntil.isAfter(now)) {
            return AcquireResult.InProgress(Duration.between(now, current.leaseUntil))
        }

        val reclaimed = auditedUpdateAll(
            predicate = {
                (table.id eq current.id) and
                        (table.status eq IdempotencyStatus.IN_PROGRESS) and
                        (table.ownerToken eq current.ownerToken) and
                        (table.leaseUntil eq current.leaseUntil)
            }
        ) {
            it[HttpIdempotencyTable.ownerToken] = ownerToken
            it[leaseUntil] = now.plus(lease)
        } == 1

        current = findByScope(scope) ?: error("idempotency record disappeared")
        return if (reclaimed) {
            log.warn {
                "idempotency_lease_reclaimed operation=${scope.operation} keyHashPrefix=${scope.keyHash.take(12)}"
            }
            AcquireResult.Acquired(current)
        } else {
            AcquireResult.InProgress(Duration.between(now, current.leaseUntil).coerceAtLeast(Duration.ZERO))
        }
    }

    /**
     * finalize은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param ownerToken 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param body 주문 라이프사이클과 fulfillment 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param failed 업무 처리에 필요한 failed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun finalize(
        id: Long,
        ownerToken: UUID,
        status: Int,
        body: String,
        failed: Boolean,
    ): Boolean =
        auditedUpdateAll(
            predicate = {
                (table.id eq id) and
                        (table.ownerToken eq ownerToken) and
                        (table.status eq IdempotencyStatus.IN_PROGRESS)
            }
        ) {
            it[HttpIdempotencyTable.status] = if (failed) IdempotencyStatus.FAILED else IdempotencyStatus.SUCCEEDED
            it[responseStatus] = status
            it[responseBody] = body.take(MAX_RESPONSE_BODY)
        }.also { updated ->
            log.debug { "idempotency_record_finalized id=$id status=$status failed=$failed updated=$updated" }
        } == 1

    /**
     * findByScope은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun findByScope(scope: IdempotencyScope): IdempotencyRecord? =
        table
            .selectAll()
            .where {
                (table.tenantId eq scope.tenantId) and
                        (table.operation eq scope.operation) and
                        (table.keyHash eq scope.keyHash)
            }.firstOrNull()
            ?.toEntity()

    /** recoverable owner는 보존하면서 만료된 terminal response를 bounded batch로 삭제합니다. */
    /** recoverable owner는 보존하면서 만료된 terminal response를 bounded batch로 삭제합니다. */
    fun deleteExpiredTerminal(
        now: Instant,
        limit: Int,
    ): Int {
        limit.requireInRange(1, MAX_CLEANUP_BATCH, "limit")
        val candidateIds =
            table
                .selectAll()
                .where {
                    ((table.status eq IdempotencyStatus.SUCCEEDED) or (table.status eq IdempotencyStatus.FAILED)) and
                            (table.expiresAt lessEq now)
                }.orderBy(table.expiresAt to SortOrder.ASC, table.id to SortOrder.ASC)
                .limit(limit)
                .map { it[table.id] }

        if (candidateIds.isEmpty()) return 0

        return table.deleteWhere { table.id inList candidateIds }
            .also { deleted ->
                log.debug { "idempotency_terminal_cleanup deleted=$deleted requestedLimit=$limit" }
            }
    }

}
