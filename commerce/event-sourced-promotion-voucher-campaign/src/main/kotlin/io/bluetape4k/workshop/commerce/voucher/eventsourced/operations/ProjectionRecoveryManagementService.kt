package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.MAX_PROJECTION_POISON_ATTEMPTS
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGeneration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLease
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLeaseRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonRecord
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionReconciliationSnapshot
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRecoveryStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.findGeneration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*
/**
 * PoisonRetryRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property identity 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property key 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property eventId 이벤트의 불변 식별자입니다. inbox/outbox 중복 제거와 감사 추적의 기준이므로 재시도에도 같은 값을 유지합니다.
 * @property expectedToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class PoisonRetryRequest(
    /**
     * `identity`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val identity: RebuildRequestIdentity,
    /**
     * `key`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val key: ProjectionKey,
    /**
     * `eventId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `expectedToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val expectedToken: Long,
)
/**
 * ProjectionReconciliationRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property identity 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property key 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property expectedToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class ProjectionReconciliationRequest(
    /**
     * `identity`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val identity: RebuildRequestIdentity,
    /**
     * `key`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val key: ProjectionKey,
    /**
     * `expectedToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val expectedToken: Long,
)

/**
 * PoisonRetryResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface PoisonRetryResult {
/**
 * Resolved은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property poison 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property checkpointPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */

    data class Resolved(
        /**
         * `poison`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val poison: ProjectionPoisonRecord,
        /**
         * `checkpointPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val checkpointPosition: Long,
        /**
         * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val replayed: Boolean,
    ): PoisonRetryResult
/**
 * RetryLater은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property delay 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


    data class RetryLater(val delay: Duration): PoisonRetryResult
/**
 * StaleToken은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property currentToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


    data class StaleToken(val currentToken: Long): PoisonRetryResult
/**
 * Conflict은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property reason 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Conflict(val reason: String): PoisonRetryResult

    /**
     * NotFound은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object NotFound: PoisonRetryResult
}

/**
 * ProjectionReconciliationResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface ProjectionReconciliationResult {
/**
 * Completed은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property snapshot 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */

    data class Completed(
        /**
         * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val snapshot: ProjectionReconciliationSnapshot,
        /**
         * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val replayed: Boolean,
    ): ProjectionReconciliationResult
/**
 * StaleToken은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property currentToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


    data class StaleToken(val currentToken: Long): ProjectionReconciliationResult

    /**
     * NotFound은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object NotFound: ProjectionReconciliationResult
}
/**
 * ProjectionRecoveryPersistence은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property projections 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property leases 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property poisons 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property recovery 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property audits 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class ProjectionRecoveryPersistence(
    /**
     * `projections`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val projections: ProjectionRepository,
    /**
     * `leases`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val leases: ProjectionLeaseRepository,
    /**
     * `poisons`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val poisons: ProjectionPoisonStore,
    /**
     * `recovery`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val recovery: ProjectionRecoveryStore,
    /**
     * `audits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val audits: OperatorAuditRepository,
)
/**
 * PoisonRetryResources은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property event 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property lease 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property generation 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


private data class PoisonRetryResources(
    /**
     * `event`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val event: EventEnvelope,
    /**
     * `lease`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val lease: ProjectionLease,
    /**
     * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: ProjectionGeneration,
)
/**
 * ProjectionRecoveryManagementService은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property transactions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property persistence 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property hmacKeyRing 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class ProjectionRecoveryManagementService(
    /**
     * `transactions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions: EventSourcedPermitTransactionRunner,
    /**
     * `persistence`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val persistence: ProjectionRecoveryPersistence,
    /**
     * `hmacKeyRing`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val hmacKeyRing: EventSourcedHmacKeyRing,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {

    private companion object: KLogging()

    /**
     * retryPoison은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun retryPoison(request: PoisonRetryRequest): PoisonRetryResult = transactions.inTransaction {
        val expectedToken = request.expectedToken.requirePositiveNumber("expectedToken")
        val auditIdentity = request.identity.toAuditIdentity(
            hmacKeyRing,
            "POISON_RETRY",
            listOf(request.key.projection, request.key.generation, request.eventId, expectedToken)
                .joinToString("\u0000"),
        )
        replayedPoisonRetry(request, auditIdentity)?.let { return@inTransaction it }

        val generation = findGeneration(request.key) ?: return@inTransaction PoisonRetryResult.NotFound
        if (generation.fencingToken != expectedToken) {
            return@inTransaction PoisonRetryResult.StaleToken(generation.fencingToken)
        }

        val poison = persistence.poisons.find(request.key, request.eventId)
            ?: return@inTransaction PoisonRetryResult.NotFound

        val now = clock.instant()
        poisonRetryBlocker(poison, now)?.let { return@inTransaction it }
        applyPoisonRetry(request, expectedToken, generation, auditIdentity, now)
    }

    /**
     * replayedPoisonRetry은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게
     * 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun replayedPoisonRetry(
        request: PoisonRetryRequest,
        identity: OperatorAuditIdentity,
    ): PoisonRetryResult.Resolved? {
        val audit = persistence.audits.find(
            identity.tenant,
            identity.requestDigest,
            OperatorAuditAction.POISON_RETRIED,
        ) ?: return null

        check(audit.result.outcome == OperatorAuditOutcome.APPLIED)

        return persistence.poisons.find(request.key, request.eventId)?.let { poison ->
            PoisonRetryResult.Resolved(
                poison = poison,
                checkpointPosition = persistence.projections.checkpoint(request.key)?.position ?: 0L,
                replayed = true,
            )
        }
    }

    /**
     * poisonRetryBlocker은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param poison 업무 처리에 필요한 poison 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun poisonRetryBlocker(
        poison: ProjectionPoisonRecord,
        now: Instant,
    ): PoisonRetryResult? = when {
        poison.state != ProjectionPoisonState.FAILED      ->
            PoisonRetryResult.Conflict("POISON_ALREADY_RESOLVED")

        poison.attempts >= MAX_PROJECTION_POISON_ATTEMPTS ->
            PoisonRetryResult.Conflict("POISON_RETRY_EXHAUSTED")

        now.isBefore(poison.nextRetryAt)                  ->
            PoisonRetryResult.RetryLater(Duration.between(now, poison.nextRetryAt))

        else                                              -> null
    }

    /**
     * applyPoisonRetry은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게
     * 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param expectedToken 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param auditIdentity 업무 처리에 필요한 auditIdentity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun applyPoisonRetry(
        request: PoisonRetryRequest,
        expectedToken: Long,
        generation: ProjectionGeneration,
        auditIdentity: OperatorAuditIdentity,
        now: Instant,
    ): PoisonRetryResult {
        val event = persistence.recovery.event(request.eventId) ?: return PoisonRetryResult.NotFound
        val lease = persistence.leases.acquire(
            projection = request.key.projection,
            generation = request.key.generation,
            ownerDigest = auditIdentity.actorDigest.value,
            now = now,
        )
        return lease?.let {
            resolvePoisonRetry(
                request = request,
                expectedToken = expectedToken,
                auditIdentity = auditIdentity,
                now = now,
                resources = PoisonRetryResources(event, it, generation),
            )
        } ?: PoisonRetryResult.Conflict("PROJECTION_LEASE_UNAVAILABLE")
    }

    /**
     * resolvePoisonRetry은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게
     * 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param expectedToken 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param auditIdentity 업무 처리에 필요한 auditIdentity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param resources 업무 처리에 필요한 resources 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun resolvePoisonRetry(
        request: PoisonRetryRequest,
        expectedToken: Long,
        auditIdentity: OperatorAuditIdentity,
        now: Instant,
        resources: PoisonRetryResources,
    ): PoisonRetryResult.Resolved {
        val applied = persistence.projections.resolvePoison(
            key = request.key,
            lease = resources.lease,
            event = resources.event,
            now = now,
        )
        val resolved = checkNotNull(persistence.poisons.find(request.key, request.eventId))
        persistence.audits.append(
            OperatorAuditEntry(
                identity = auditIdentity,
                action = OperatorAuditAction.POISON_RETRIED,
                target = OperatorAuditTarget(request.key.projection, request.key.generation, expectedToken),
                transition = OperatorAuditTransition(
                    beforeState = resources.generation.state,
                    afterState = resources.generation.state,
                    checkpointPosition = applied.checkpoint.position,
                    streamPosition = resources.event.globalPosition,
                    reasonClass = null,
                ),
                result = OperatorAuditResult(OperatorAuditOutcome.APPLIED, now),
            ),
        )
        log.info {
            "projection_poison_resolved projection=${request.key.projection} " +
                    "generation=${request.key.generation} position=${resources.event.globalPosition}"
        }
        return PoisonRetryResult.Resolved(resolved, applied.checkpoint.position, replayed = false)
    }

    /**
     * reconcile은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun reconcile(request: ProjectionReconciliationRequest): ProjectionReconciliationResult =
        transactions.inTransaction {
            /**
             * `expectedToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
             *
             * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
             */
            val expectedToken = request.expectedToken.requirePositiveNumber("expectedToken")
            /**
             * `auditIdentity`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val auditIdentity = request.identity.toAuditIdentity(
                hmacKeyRing,
                "RECONCILIATION",
                listOf(request.key.projection, request.key.generation, expectedToken)
                    .joinToString("\u0000"),
            )
            /**
             * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
             *
             * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
             */
            val generation = findGeneration(request.key) ?: return@inTransaction ProjectionReconciliationResult.NotFound

            if (generation.fencingToken != expectedToken) {
                log.warn {
                    "projection_reconciliation_stale projection=${request.key.projection} generation=${request.key.generation}"
                }
                return@inTransaction ProjectionReconciliationResult.StaleToken(generation.fencingToken)
            }
            /**
             * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val replayed = persistence.audits.find(
                auditIdentity.tenant,
                auditIdentity.requestDigest,
                OperatorAuditAction.RECONCILIATION_RUN,
            ) != null

            /**
             * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val snapshot = persistence.recovery.reconciliation(request.key)
            if (!replayed) {
                persistence.audits.append(
                    OperatorAuditEntry(
                        identity = auditIdentity,
                        action = OperatorAuditAction.RECONCILIATION_RUN,
                        target = OperatorAuditTarget(
                            request.key.projection,
                            request.key.generation,
                            expectedToken,
                        ),
                        transition = OperatorAuditTransition(
                            beforeState = generation.state,
                            afterState = generation.state,
                            checkpointPosition = snapshot.checkpointPosition,
                            streamPosition = snapshot.streamPosition,
                            reasonClass = null,
                        ),
                        result = OperatorAuditResult(OperatorAuditOutcome.APPLIED, clock.instant()),
                    ),
                )
            }
            ProjectionReconciliationResult.Completed(snapshot, replayed)
        }
}
