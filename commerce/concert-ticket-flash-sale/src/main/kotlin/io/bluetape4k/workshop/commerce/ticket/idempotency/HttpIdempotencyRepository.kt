package io.bluetape4k.workshop.commerce.ticket.idempotency

import io.bluetape4k.javatimes.hours
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketHttpIdempotencies
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketHttpIdempotencyEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketLockRank
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.data.repository.findByIdOrNull
import java.io.Serial
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * owner scope가 완전하게 포함된 HTTP idempotency key입니다.
 * @property principalSubjectId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property httpMethod 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property canonicalRoute 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property resourceId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property operation 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property keyDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */
data class IdempotencyScope(
    /**
     * `principalSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val principalSubjectId: UUID,
    /**
     * `httpMethod`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val httpMethod: String,
    /**
     * `canonicalRoute`은 콘서트 티켓 플래시 세일에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val canonicalRoute: String,
    /**
     * `resourceId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId: String,
    /**
     * `operation`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val operation: String,
    /**
     * `keyDigest`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val keyDigest: TicketDigest,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** sale state나 Redis admission보다 먼저 평가되는 결과입니다. */
/** sale state나 Redis admission보다 먼저 평가되는 결과입니다. */
sealed interface IdempotencyDecision: Serializable {
/**
 * Owner은 콘서트 티켓 판매에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */

    data class Owner(val id: Long): IdempotencyDecision {
        companion object {
            /**
             * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }
/**
 * Replay은 콘서트 티켓 판매에서 전달되는 불변 데이터 모델입니다.
 * @property attemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property completed 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Replay(val attemptId: UUID, val completed: Boolean): IdempotencyDecision {
        companion object {
            /**
             * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * InProgress은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object InProgress: IdempotencyDecision {
        /**
         * readResolve은 콘서트 티켓 판매의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
         * 않습니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        private fun readResolve(): Any = InProgress
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */

        @Serial
        private const val serialVersionUID: Long = 1L
    }

    /**
     * Conflict은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object Conflict: IdempotencyDecision {
        /**
         * readResolve은 콘서트 티켓 판매의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
         * 않습니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        private fun readResolve(): Any = Conflict
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */

        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * principal scope replay와 fingerprint conflict를 담당하는 PostgreSQL authority입니다.
 * @property jdbc 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property retention 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */
class HttpIdempotencyRepository(
    /**
     * `jdbc`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc: TicketJdbcExecutor,
    /**
     * `retention`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val retention: Duration = 24.hours(),
): TicketExposedJdbcRepository<TicketHttpIdempotencyEntity, Long>(TicketHttpIdempotencyEntity::class) {

    /**
     * acquire은 콘서트 티켓 판매의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param fingerprint 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun acquire(
        scope: IdempotencyScope,
        fingerprint: TicketDigest,
        now: Instant,
    ): IdempotencyDecision =
        try {
            jdbc.transaction {
                acquire(TicketLockRank.IDEMPOTENCY)
                /**
                 * `id`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
                 *
                 * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
                 */
                val id = TicketHttpIdempotencies.insertAndGetId {
                    it[principalSubjectId] = scope.principalSubjectId
                    it[httpMethod] = scope.httpMethod
                    it[canonicalRoute] = scope.canonicalRoute
                    it[resourceId] = scope.resourceId
                    it[operation] = scope.operation
                    it[idempotencyKeyDigest] = scope.keyDigest.bytes()
                    it[requestFingerprint] = fingerprint.bytes()
                    it[status] = "in_progress"
                    it[expiresAt] = now + retention
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value
                IdempotencyDecision.Owner(id)
            }
        } catch (failure: ExposedSQLException) {
            if (failure.sqlState != UNIQUE_VIOLATION) throw failure
            existing(scope, fingerprint)
        }

    /**
     * attachAttempt은 콘서트 티켓 판매 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param attemptId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun attachAttempt(id: Long, attemptId: UUID) {
        jdbc.transaction {
            acquire(TicketLockRank.IDEMPOTENCY)
            val entity = findByIdOrNull(id) ?: error("idempotency row not found")
            entity.attemptId = attemptId
            entity.updatedAt = Instant.now()
            save(entity)
        }
    }

    /**
     * existing은 콘서트 티켓 판매 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param fingerprint 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    private fun existing(
        scope: IdempotencyScope,
        fingerprint: TicketDigest,
    ): IdempotencyDecision =
        jdbc.transaction {
            acquire(TicketLockRank.IDEMPOTENCY)
            /**
             * `entity`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val entity = TicketHttpIdempotencies.selectAll()
                .where {
                    (TicketHttpIdempotencies.principalSubjectId eq scope.principalSubjectId) and
                            (TicketHttpIdempotencies.httpMethod eq scope.httpMethod) and
                            (TicketHttpIdempotencies.canonicalRoute eq scope.canonicalRoute) and
                            (TicketHttpIdempotencies.resourceId eq scope.resourceId) and
                            (TicketHttpIdempotencies.operation eq scope.operation) and
                            (TicketHttpIdempotencies.idempotencyKeyDigest eq scope.keyDigest.bytes())
                }
                .forUpdate()
                .singleOrNull()
                ?.let { findByIdOrNull(it[TicketHttpIdempotencies.id].value) }
                ?: error("idempotency winner disappeared")

            if (!entity.requestFingerprint.contentEquals(fingerprint.bytes())) {
                IdempotencyDecision.Conflict
            } else {
                entity.attemptId
                    ?.let { IdempotencyDecision.Replay(it, entity.status == "completed") }
                    ?: IdempotencyDecision.InProgress
            }
        }

    companion object {
        /**
         * `UNIQUE_VIOLATION`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private const val UNIQUE_VIOLATION = "23505"
    }
}
