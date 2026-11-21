package io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requireLt
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.IdempotencyReceipts
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant

/**
 * `FIRST_ERROR_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val FIRST_ERROR_STATUS = 400
/**
 * `DEFAULT_RECEIPT_LEASE_SECONDS`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val DEFAULT_RECEIPT_LEASE_SECONDS = 90L
/**
 * `DEFAULT_RECEIPT_COMMAND_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_RECEIPT_COMMAND_TIMEOUT_SECONDS = 60L
/**
 * `DEFAULT_RECEIPT_LEASE`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private val DEFAULT_RECEIPT_LEASE: Duration = DEFAULT_RECEIPT_LEASE_SECONDS.seconds()
/**
 * `DEFAULT_RECEIPT_COMMAND_TIMEOUT`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private val DEFAULT_RECEIPT_COMMAND_TIMEOUT: Duration = DEFAULT_RECEIPT_COMMAND_TIMEOUT_SECONDS.seconds()
/**
 * ReceiptAcquireRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property scope 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property fingerprint 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property now 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property lease 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property commandTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


private data class ReceiptAcquireRequest(
    /**
     * `scope`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scope: ReceiptScope,
    /**
     * `fingerprint`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fingerprint: ReceiptDigest,
    /**
     * `now`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val now: Instant,
    /**
     * `ownerToken`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerToken: ReceiptOwnerToken = ReceiptOwnerToken.random(),
    /**
     * `lease`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val lease: Duration = DEFAULT_RECEIPT_LEASE,
    /**
     * `commandTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val commandTimeout: Duration = DEFAULT_RECEIPT_COMMAND_TIMEOUT,
)

/**
 * PostgreSQL receipt authority입니다. 모든 mutating call은 active foreground transaction을 요구합니다.
 * command orchestration은 짧은 acquire transaction과 append/finalize transaction boundary를 소유합니다.
 */
internal class EventSourcedIdempotencyRepository {

    /**
     * acquire은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param fingerprint 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun acquire(
        scope: ReceiptScope,
        fingerprint: ReceiptDigest,
        now: Instant,
    ): ReceiptAcquireResult =
        acquire(ReceiptAcquireRequest(scope, fingerprint, now))

    /**
     * acquire은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun acquire(request: ReceiptAcquireRequest): ReceiptAcquireResult =
        with(request) {
            TransactionManager.current()
            validateTiming(lease, commandTimeout)
            /**
             * 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
             */
            val leaseDeadline = now.plus(lease)
            /**
             * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
             */
            val requestFingerprint = fingerprint.value
            /**
             * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
             */

            val inserted = IdempotencyReceipts.insertIgnore { row ->
                row[IdempotencyReceipts.tenantId] = scope.tenantId.value
                row[IdempotencyReceipts.principalDigest] = scope.principalDigest.value
                row[IdempotencyReceipts.operation] = scope.operation
                row[IdempotencyReceipts.resourceId] = scope.resourceId
                row[IdempotencyReceipts.keyDigest] = scope.keyDigest.value
                row[IdempotencyReceipts.fingerprint] = requestFingerprint
                row[IdempotencyReceipts.status] = ReceiptStatus.IN_PROGRESS
                row[IdempotencyReceipts.ownerTokenDigest] = ownerToken.digest().value
                row[IdempotencyReceipts.leaseDeadline] = leaseDeadline
                row[IdempotencyReceipts.commandDeadline] = now.plus(commandTimeout)
                row[IdempotencyReceipts.createdAt] = now
                row[IdempotencyReceipts.updatedAt] = now
            }.insertedCount == 1

            if (inserted) {
                log.debug { "voucher_receipt_acquired operation=${scope.operation}" }
                ReceiptAcquireResult.Owner(ownerToken, leaseDeadline)
            } else {
                acquireExisting(request, leaseDeadline)
            }
        }

    /**
     * acquireExisting은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param leaseDeadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun acquireExisting(
        request: ReceiptAcquireRequest,
        leaseDeadline: Instant,
    ): ReceiptAcquireResult {
        val scope = request.scope
        val fingerprint = request.fingerprint
        val now = request.now
        val ownerToken = request.ownerToken
        val current = find(scope).checkNotNull { "idempotency receipt disappeared after duplicate acquire" }
        val currentLease = current.leaseDeadline

        return when {
            current.fingerprint != fingerprint.value -> {
                log.warn { "voucher_receipt_fingerprint_conflict operation=${scope.operation}" }
                ReceiptAcquireResult.FingerprintConflict
            }
            current.descriptor != null               ->
                ReceiptAcquireResult.Replay(
                    current.descriptor
                )
            currentLease.checkNotNull { "in-progress receipt must have a lease" }
                .isAfter(now)                        ->
                ReceiptAcquireResult.InProgress(Duration.between(now, currentLease))

            reclaimExpiredLease(request, current, leaseDeadline) -> {
                log.warn { "voucher_receipt_lease_taken_over operation=${scope.operation}" }
                ReceiptAcquireResult.Owner(ownerToken, leaseDeadline)
            }
            else                                     ->
                retryAcquire(scope, now)
        }
    }

    /**
     * reclaimExpiredLease은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param current 업무 처리에 필요한 current 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param leaseDeadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun reclaimExpiredLease(
        request: ReceiptAcquireRequest,
        current: StoredReceipt,
        leaseDeadline: Instant,
    ): Boolean {
        val scope = request.scope
        val now = request.now
        val ownerToken = request.ownerToken
        val commandTimeout = request.commandTimeout
        val currentLease = current.leaseDeadline.checkNotNull { "in-progress receipt must have a lease" }

        val whereOp = {
            scopePredicate(scope) and
                    (IdempotencyReceipts.status eq ReceiptStatus.IN_PROGRESS) and
                    (IdempotencyReceipts.ownerTokenDigest eq current.ownerTokenDigest) and
                    (IdempotencyReceipts.leaseDeadline eq currentLease)
        }
        return IdempotencyReceipts.update(whereOp) { row ->
            row[IdempotencyReceipts.ownerTokenDigest] = ownerToken.digest().value
            row[IdempotencyReceipts.leaseDeadline] = leaseDeadline
            row[IdempotencyReceipts.commandDeadline] = now.plus(commandTimeout)
            row[IdempotencyReceipts.updatedAt] = now
        } == 1
    }

    /**
     * retryAcquire은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun retryAcquire(
        scope: ReceiptScope,
        now: Instant,
    ): ReceiptAcquireResult {
        val retried = find(scope).checkNotNull { "idempotency receipt disappeared after lease takeover race" }

        return retried.descriptor?.let(ReceiptAcquireResult::Replay)
            ?: ReceiptAcquireResult.InProgress(
                Duration.between(now, retried.leaseDeadline).coerceAtLeast(Duration.ZERO),
            )
    }

    /**
     * isOwner은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param fingerprint 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ownerToken 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun isOwner(
        scope: ReceiptScope,
        fingerprint: ReceiptDigest,
        ownerToken: ReceiptOwnerToken,
        now: Instant,
    ): Boolean {
        TransactionManager.current()

        return IdempotencyReceipts.selectAll()
            .where {
                scopePredicate(scope) and
                        (IdempotencyReceipts.fingerprint eq fingerprint.value) and
                        (IdempotencyReceipts.status eq ReceiptStatus.IN_PROGRESS) and
                        (IdempotencyReceipts.ownerTokenDigest eq ownerToken.digest().value) and
                        (IdempotencyReceipts.leaseDeadline greaterEq now) and
                        (IdempotencyReceipts.commandDeadline greaterEq now)
            }.count() == 1L
    }

    /**
     * finalize은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param fingerprint 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param ownerToken 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param descriptor 업무 처리에 필요한 descriptor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun finalize(
        scope: ReceiptScope,
        fingerprint: ReceiptDigest,
        ownerToken: ReceiptOwnerToken,
        now: Instant,
        descriptor: TerminalDescriptor,
    ): Boolean {
        TransactionManager.current()

        val whereOp = {
            scopePredicate(scope) and
                    (IdempotencyReceipts.fingerprint eq fingerprint.value) and
                    (IdempotencyReceipts.status eq ReceiptStatus.IN_PROGRESS) and
                    (IdempotencyReceipts.ownerTokenDigest eq ownerToken.digest().value) and
                    (IdempotencyReceipts.leaseDeadline greaterEq now) and
                    (IdempotencyReceipts.commandDeadline greaterEq now)
        }
        val finalized = IdempotencyReceipts.update(whereOp) { row ->
            row[IdempotencyReceipts.status] =
                if (descriptor.status < FIRST_ERROR_STATUS) ReceiptStatus.SUCCEEDED else ReceiptStatus.FAILED
            row[IdempotencyReceipts.terminalOutcome] = descriptor.outcome
            row[IdempotencyReceipts.terminalStatus] = descriptor.status
            row[IdempotencyReceipts.allocationId] = descriptor.allocationId
            row[IdempotencyReceipts.hmacKeyVersion] = descriptor.hmacKeyVersion
            row[IdempotencyReceipts.generationKeyVersion] = descriptor.generationKeyVersion
            row[IdempotencyReceipts.verificationKeyVersion] = descriptor.verificationKeyVersion
            row[IdempotencyReceipts.terminalObservedAt] = descriptor.observedAt
            row[IdempotencyReceipts.terminalStreamPosition] = descriptor.streamPosition
            row[IdempotencyReceipts.ownerTokenDigest] = null
            row[IdempotencyReceipts.leaseDeadline] = null
            row[IdempotencyReceipts.updatedAt] = now
        } == 1
        log.debug { "voucher_receipt_finalized operation=${scope.operation} finalized=$finalized" }
        return finalized
    }

    companion object: KLogging()
}
/**
 * StoredReceipt은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property fingerprint 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property ownerTokenDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property leaseDeadline 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property descriptor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


private data class StoredReceipt(
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val fingerprint: String,
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val ownerTokenDigest: String?,
    /**
     * 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val leaseDeadline: Instant?,
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val descriptor: TerminalDescriptor?,
)

/**
 * find은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun find(scope: ReceiptScope): StoredReceipt? =
    IdempotencyReceipts
        .selectAll()
        .where { scopePredicate(scope) }
        .singleOrNull()
        ?.let(::toStoredReceipt)

/**
 * scopePredicate은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun scopePredicate(scope: ReceiptScope) =
    (IdempotencyReceipts.tenantId eq scope.tenantId.value) and
            (IdempotencyReceipts.principalDigest eq scope.principalDigest.value) and
            (IdempotencyReceipts.operation eq scope.operation) and
            (IdempotencyReceipts.resourceId eq scope.resourceId) and
            (IdempotencyReceipts.keyDigest eq scope.keyDigest.value)

/**
 * toStoredReceipt은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun toStoredReceipt(row: ResultRow): StoredReceipt {
    val status = row[IdempotencyReceipts.status]
    val descriptor = if (status == ReceiptStatus.IN_PROGRESS) {
        null
    } else {
        TerminalDescriptor(
            outcome = row[IdempotencyReceipts.terminalOutcome].checkNotNull("terminalOutcome"),
            status = row[IdempotencyReceipts.terminalStatus].checkNotNull("terminalStatus"),
            keyVersions = TerminalKeyVersions(
                hmac = row[IdempotencyReceipts.hmacKeyVersion].checkNotNull("hmacKeyVersion"),
                generationKeyVersion = row[IdempotencyReceipts.generationKeyVersion],
                verificationKeyVersion = row[IdempotencyReceipts.verificationKeyVersion],
            ),
            allocationId = row[IdempotencyReceipts.allocationId],
        ).let { descriptor ->
            row[IdempotencyReceipts.terminalObservedAt]?.let(descriptor::withObservedAt) ?: descriptor
        }.let { descriptor ->
            row[IdempotencyReceipts.terminalStreamPosition]?.let(descriptor::withStreamPosition) ?: descriptor
        }
    }

    return StoredReceipt(
        fingerprint = row[IdempotencyReceipts.fingerprint],
        ownerTokenDigest = row[IdempotencyReceipts.ownerTokenDigest],
        leaseDeadline = row[IdempotencyReceipts.leaseDeadline],
        descriptor = descriptor,
    )
}

/**
 * validateTiming은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param commandTimeout 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
 */
private fun validateTiming(
    lease: Duration,
    commandTimeout: Duration,
) {
    lease.requirePositiveDuration("lease")
    commandTimeout.requirePositiveDuration("commandTimeout")
    commandTimeout.requireLt(lease, "commandTimeout")
}
