package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.idempotency.Digest
import io.bluetape4k.workshop.commerce.voucher.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyAcquireResult
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyScope
import io.bluetape4k.workshop.commerce.voucher.idempotency.OwnerToken
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherIdempotencyStore
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import org.springframework.stereotype.Service
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
/**
 * IdempotentVoucherCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property scope 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property fingerprint 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property lease 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property commandTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property retention 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


internal data class IdempotentVoucherCommand(
    /**
     * `scope`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scope: IdempotencyScope,
    /**
     * `fingerprint`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fingerprint: Digest,
    /**
     * `lease`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val lease: Duration = HttpIdempotencyRepository.DEFAULT_LEASE,
    /**
     * `commandTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val commandTimeout: Duration = HttpIdempotencyRepository.DEFAULT_COMMAND_TIMEOUT,
    /**
     * `retention`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retention: Duration = HttpIdempotencyRepository.DEFAULT_RETENTION,
)

/**
 * IdempotentCommandResult은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface IdempotentCommandResult: Serializable {
/**
 * Completed은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property response 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */


    data class Completed(
        /**
         * `response`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val response: StoredHttpResponse,
        /**
         * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val replayed: Boolean,
    ): IdempotentCommandResult
/**
 * Retryable은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property response 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 */


    data class Retryable(
        /**
         * `response`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val response: StoredHttpResponse,
    ): IdempotentCommandResult
/**
 * InProgress은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property retryAfter 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께
 * 적용합니다.
 */


    data class InProgress(
        /**
         * `retryAfter`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val retryAfter: Duration,
    ): IdempotentCommandResult

    /**
     * FingerprintConflict은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object FingerprintConflict: IdempotentCommandResult {
        /**
         * readResolve은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        private fun readResolve(): Any = FingerprintConflict
    }
}

/**
 * `IdempotencyCutPoint`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class IdempotencyCutPoint {
    /**
     * `IdempotencyCutPoint.AFTER_ACQUIRE`은 주 side effect commit 뒤 실행하는 후속 이벤트·감사 단계입니다.
     */
    AFTER_ACQUIRE,
    /**
     * `IdempotencyCutPoint.BEFORE_BUSINESS`은 주 side effect 전에 실행하는 검증·정책 단계입니다.
     */
    BEFORE_BUSINESS,
    /**
     * `IdempotencyCutPoint.AFTER_FINALIZE_BEFORE_COMMIT`은 주 side effect commit 뒤 실행하는 후속 이벤트·감사
     * 단계입니다.
     */
    AFTER_FINALIZE_BEFORE_COMMIT,
    /**
     * `IdempotencyCutPoint.AFTER_COMMIT_BEFORE_RESPONSE`은 주 side effect commit 뒤 실행하는 후속 이벤트·감사
     * 단계입니다.
     */
    AFTER_COMMIT_BEFORE_RESPONSE,
}

/**
 * 감싸는 business transaction이 rollback되도록 retryable command outcome을 신호합니다.
 * @property response 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */
internal class RetryableVoucherCommand(
    /**
     * `response`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val response: StoredHttpResponse,
): RuntimeException(response.responseKind.name) {

    init {
        require(response.responseKind in RETRYABLE_KINDS) { "response kind is not retryable with the same key" }
    }

    companion object: KLogging() {
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val RETRYABLE_KINDS = setOf(
            VoucherResponseKind.RATE_LIMITED,
            VoucherResponseKind.DATABASE_BULKHEAD_REJECTED,
            VoucherResponseKind.AUTHORITATIVE_BACKEND_UNAVAILABLE,
            VoucherResponseKind.CAMPAIGN_PAUSED,
            VoucherResponseKind.IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE,
            VoucherResponseKind.RECONCILIATION_IN_PROGRESS,
        )
    }
}

/**
 * replay, admission, acquisition, business transaction을 분리해 virtual thread가
 * JDBC permit이나 connection을 보유한 채 Redis/risk work를 기다리지 않게 합니다.
 * @property idempotency 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property transactions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property ownerTokens 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property cutPoint 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Service
internal class IdempotentVoucherCommandService(
    /**
     * `idempotency`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val idempotency: VoucherIdempotencyStore,
    /**
     * `transactions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions: VoucherTransactionRunner,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
    /**
     * `ownerTokens`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val ownerTokens: () -> OwnerToken = OwnerToken::random,
    /**
     * `cutPoint`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val cutPoint: (IdempotencyCutPoint) -> Unit = {},
) {

    companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val RETRYABLE_WITHOUT_OWNER = setOf(
            VoucherResponseKind.RATE_LIMITED,
            VoucherResponseKind.DATABASE_BULKHEAD_REJECTED,
            VoucherResponseKind.AUTHORITATIVE_BACKEND_UNAVAILABLE,
            VoucherResponseKind.CAMPAIGN_PAUSED,
            VoucherResponseKind.IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE,
            VoucherResponseKind.RECONCILIATION_IN_PROGRESS,
        )
    }

    /**
     * `execute`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = execute(command = command, admission = admission, business = business)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @param admission 요청 경계에서 검증된 `admission`입니다.
     * @param business 요청 경계에서 검증된 `business`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun execute(
        command: IdempotentVoucherCommand,
        admission: () -> StoredHttpResponse?,
        business: () -> StoredHttpResponse,
    ): IdempotentCommandResult =
        executeOwned(command, admission, transactionalBusiness = true, business)

    /**
     * bounded reconciliation batch처럼 business operation이 더 작은 독립 transaction을 소유하는 command를 실행합니다.
     * owner validation과 terminal replay persistence는 PostgreSQL-authoritative로 유지하지만,
     * external work 전체에 foreground permit을 보유하지 않습니다.
     *
     * 예:
     * ```kotlin
     * val result = executeExternal(command, admission = { null }) {
     *     reconciliationService.runBatch(command)
     * }
     * // 동일 fingerprint의 재전송은 새 외부 작업 대신 저장된 terminal 결과를 replay한다.
     * ```
     */
    fun executeExternal(
        command: IdempotentVoucherCommand,
        admission: () -> StoredHttpResponse?,
        business: () -> StoredHttpResponse,
    ): IdempotentCommandResult =
        executeOwned(command, admission, transactionalBusiness = false, business)

    /**
     * executeOwned은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param admission 업무 처리에 필요한 admission 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param transactionalBusiness 업무 처리에 필요한 transactionalBusiness 값이며 관련 불변조건과 저장·이벤트 계약을 함께
     * 만족해야 합니다.
     * @param business 업무 처리에 필요한 business 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private inline fun executeOwned(
        command: IdempotentVoucherCommand,
        admission: () -> StoredHttpResponse?,
        transactionalBusiness: Boolean,
        crossinline business: () -> StoredHttpResponse,
    ): IdempotentCommandResult {
        val replay = transactions.foregroundTransaction {
            idempotency.lookup(command.scope, command.fingerprint)
        }
        terminalResult(replay)?.let { return it }

        admission()?.let { rejected ->
            require(rejected.responseKind in RETRYABLE_WITHOUT_OWNER) {
                "admission may return only retryable non-terminal outcomes"
            }
            return IdempotentCommandResult.Retryable(rejected)
        }

        val now = Instant.now(clock)
        val acquired: IdempotencyAcquireResult = transactions.foregroundTransaction {
            idempotency.acquire(
                scope = command.scope,
                fingerprint = command.fingerprint,
                now = now,
                ownerToken = ownerTokens(),
                lease = command.lease,
                commandTimeout = command.commandTimeout,
                retention = command.retention,
            )
        }
        terminalResult(acquired)?.let { return it }

        if (acquired is IdempotencyAcquireResult.InProgress) {
            return IdempotentCommandResult.InProgress(acquired.retryAfter.coerceAtMost(command.lease))
        }
        check(acquired is IdempotencyAcquireResult.Owner) {
            "unexpected idempotency acquisition result"
        }
        cutPoint(IdempotencyCutPoint.AFTER_ACQUIRE)

        val response = try {
            if (transactionalBusiness) {
                transactions.foregroundTransaction {
                    requireOwner(command, acquired, Instant.now(clock))
                    cutPoint(IdempotencyCutPoint.BEFORE_BUSINESS)
                    val completed = business()
                    finalize(command, acquired, completed)
                    completed
                }
            } else {
                transactions.foregroundTransaction {
                    requireOwner(command, acquired, Instant.now(clock))
                }
                cutPoint(IdempotencyCutPoint.BEFORE_BUSINESS)
                val completed = business()
                transactions.foregroundTransaction {
                    finalize(command, acquired, completed)
                }
                completed
            }
        } catch (retryable: RetryableVoucherCommand) {
            transactions.foregroundTransaction {
                idempotency.release(command.scope, acquired.ownerToken)
            }
            log.warn {
                "voucher_command_retryable operation=${command.scope.operation} " +
                        "kind=${retryable.response.responseKind} " +
                        "keyDigestPrefix=${command.scope.keyDigest.base64Url.take(12)}"
            }
            return IdempotentCommandResult.Retryable(retryable.response)
        }

        cutPoint(IdempotencyCutPoint.AFTER_COMMIT_BEFORE_RESPONSE)

        log.debug {
            "voucher_command_completed operation=${command.scope.operation} " +
                    "responseKind=${response.responseKind} " +
                    "keyDigestPrefix=${command.scope.keyDigest.base64Url.take(12)}"
        }

        return IdempotentCommandResult.Completed(response, replayed = false)
    }

    /**
     * requireOwner은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param acquired 업무 처리에 필요한 acquired 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun requireOwner(
        command: IdempotentVoucherCommand,
        acquired: IdempotencyAcquireResult.Owner,
        now: Instant,
    ) {
        val isOwner = idempotency.isOwner(command.scope, acquired.ownerToken, now)
        check(isOwner) { "idempotency owner or command deadline was lost before business mutation" }
    }

    /**
     * finalize은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param acquired 업무 처리에 필요한 acquired 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param response 업무 처리에 필요한 response 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun finalize(
        command: IdempotentVoucherCommand,
        acquired: IdempotencyAcquireResult.Owner,
        response: StoredHttpResponse,
    ) {
        val finalized = idempotency.finalize(command.scope, acquired.ownerToken, Instant.now(clock), response)
        check(finalized) { "idempotency owner was lost before terminal finalize" }

        cutPoint(IdempotencyCutPoint.AFTER_FINALIZE_BEFORE_COMMIT)
    }

    /**
     * terminalResult은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    private fun terminalResult(
        result: IdempotencyAcquireResult?,
    ): IdempotentCommandResult? = when (result) {
        is IdempotencyAcquireResult.Replay           ->
            IdempotentCommandResult.Completed(
                result.response,
                replayed = true
            )
        IdempotencyAcquireResult.FingerprintConflict -> IdempotentCommandResult.FingerprintConflict
        else                                         -> null
    }
}
