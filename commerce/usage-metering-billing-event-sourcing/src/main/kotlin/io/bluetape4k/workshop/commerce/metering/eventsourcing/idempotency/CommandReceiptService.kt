package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptCompletion
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptInsert
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptSnapshot
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptStatus
import java.time.Duration
import java.time.Instant
import java.util.*
/**
 * CommandScope은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property operation 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property keyDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


data class CommandScope(val tenantId: String, val operation: String, val keyDigest: CommandDigest)

/**
 * CommandAcquireResult은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
sealed interface CommandAcquireResult {
/**
 * Owned은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property receiptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */

    data class Owned(val receiptId: UUID, val ownerToken: UUID): CommandAcquireResult
/**
 * Replay은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property httpStatus 현재 사용량 계량·청구 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property response 사용량 계량·청구 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 */

    data class Replay(val httpStatus: Int, val response: String): CommandAcquireResult
/**
 * InProgress은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property retryAfter 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께
 * 적용합니다.
 */

    data class InProgress(val retryAfter: Duration): CommandAcquireResult
    /**
     * Conflict은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object Conflict: CommandAcquireResult
}

/**
 * CommandOwnerLostException은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class CommandOwnerLostException: IllegalStateException("command_receipt_owner_lost")
/**
 * CommandReceiptService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property repository 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property lease 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property retention 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */


class CommandReceiptService(
    /**
     * `repository`은 사용량 과금·정산에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: CommandReceiptRepository,
    /**
     * `lease`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val lease: Duration,
    /**
     * `retention`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val retention: Duration,
) {

    private companion object: KLogging() {
        /**
         * `MAX_RESPONSE_BYTES`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val MAX_RESPONSE_BYTES = 16 * 1024
    }

    init {
        lease.requirePositiveDuration("lease")
        retention.requireGt(lease, "retention")
    }

    /**
     * acquire은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param fingerprint 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun acquire(scope: CommandScope, fingerprint: CommandDigest, now: Instant): CommandAcquireResult {
        // ownerToken은 takeover 방지용 opaque 보안 토큰이므로 UUID v7로 정렬하지 않는다.
        val ownerToken = UUID.randomUUID()
        repository.insertOwnerIfAbsent(
            CommandReceiptInsert(
                scope = scope,
                fingerprint = fingerprint,
                ownerToken = ownerToken,
                leaseUntil = now.plus(lease),
                retentionUntil = now.plus(retention),
                now = now
            ),
        )

        val current = repository.find(scope).checkNotNull { "command_receipt_not_found" }

        return when {
            current.fingerprint != fingerprint               ->
                CommandAcquireResult.Conflict
            current.status == CommandReceiptStatus.SUCCEEDED ->
                CommandAcquireResult.Replay(current.httpStatus!!, current.response!!)
            current.leaseUntil.isAfter(now)                  ->
                activeLeaseResult(current, ownerToken, now)
            else                                             ->
                takeoverResult(current, now)
        }
    }

    /**
     * activeLeaseResult은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param current 업무 처리에 필요한 current 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param attemptedOwner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun activeLeaseResult(
        current: CommandReceiptSnapshot,
        attemptedOwner: UUID,
        now: Instant,
    ): CommandAcquireResult =
        if (current.ownerToken == attemptedOwner) {
            CommandAcquireResult.Owned(current.id, attemptedOwner)
        } else {
            CommandAcquireResult.InProgress(Duration.between(now, current.leaseUntil))
        }

    /**
     * takeoverResult은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param current 업무 처리에 필요한 current 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun takeoverResult(
        current: CommandReceiptSnapshot,
        now: Instant,
    ): CommandAcquireResult {
        // replacement도 기존 owner를 fencing하는 opaque 보안 토큰이며 documented raw fallback이다.
        val replacement = UUID.randomUUID()

        val takeovered = repository.takeover(
            current = current,
            owner = replacement,
            leaseUntil = now.plus(lease),
            retentionUntil = now.plus(retention),
            now = now
        )

        return if (takeovered) {
            CommandAcquireResult.Owned(current.id, replacement)
        } else {
            CommandAcquireResult.InProgress(lease)
        }
    }

    /**
     * succeed은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owned 업무 처리에 필요한 owned 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param httpStatus 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param response 업무 처리에 필요한 response 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun succeed(owned: CommandAcquireResult.Owned, httpStatus: Int, response: String, now: Instant): Boolean {
        require(response.toUtf8Bytes().size <= MAX_RESPONSE_BYTES) { "command_response_too_large" }
        return repository.complete(
            CommandReceiptCompletion(
                receiptId = owned.receiptId,
                owner = owned.ownerToken,
                httpStatus = httpStatus,
                response = response,
                retentionUntil = now.plus(retention),
                now = now
            ),
        )
    }

    /**
     * requireOwnership은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owned 업무 처리에 필요한 owned 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun requireOwnership(owned: CommandAcquireResult.Owned, now: Instant) {
        if (!repository.isActiveOwner(owned.receiptId, owned.ownerToken, now)) {
            throw CommandOwnerLostException()
        }
    }
}
