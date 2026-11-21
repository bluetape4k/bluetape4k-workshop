@file:Suppress("MagicNumber", "MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.EffectReference
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyOwner
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkerRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerClaimSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import java.io.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.*

/**
 * `ReconciliationCommandFailure`은 실패·오류 분류 코드입니다.
 * 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행하는 명령·신호 계약입니다.
 */
internal enum class ReconciliationCommandFailure {
    /**
     * `ReconciliationCommandFailure.SCOPE_NOT_FOUND`은 요청한 tenant·scope에 해당하는 자원이 존재하지 않는 값입니다.
     */
    SCOPE_NOT_FOUND,
    /**
     * `ReconciliationCommandFailure.COMMAND_IN_PROGRESS`은 동일 command가 이미 실행 중이어서 중복 실행을 막아야 하는
     * 값입니다.
     */
    COMMAND_IN_PROGRESS,
    /**
     * `ReconciliationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT`은 동일 key에 다른 요청 fingerprint가
     * 들어와 side
     * effect를 거부하는 값입니다.
     */
    IDEMPOTENCY_FINGERPRINT_CONFLICT,
    /**
     * `ReconciliationCommandFailure.REPLAY_WINDOW_EXPIRED`은 멱등 결과를 재생할 보존 창이 지나 원 요청을 다시 처리할 수 없는
     * 값입니다.
     */
    REPLAY_WINDOW_EXPIRED,
}
/**
 * ReconciliationCommandException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property reason 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


internal class ReconciliationCommandException(val reason: ReconciliationCommandFailure):
    IllegalStateException(reason.name)
/**
 * ReconciliationCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property batchId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class ReconciliationCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
): Serializable {
    init {
        require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH)
    }

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
 * ReconciliationProgressSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property kind 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property scopeId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property cursor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property checkpoint 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property attempt 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께 적용합니다.
 * @property nextAction 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property observedAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class ReconciliationProgressSnapshot(
    /**
     * `kind`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val kind: WorkerKind,
    /**
     * `scopeId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val scopeId: UUID,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursor: Long,
    /**
     * `checkpoint`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpoint: Long,
    /**
     * `attempt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val attempt: Int,
    /**
     * `nextAction`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val nextAction: String,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `observedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val observedAt: Instant,
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
 * VoucherPoolReconciliationCommandService은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이,
 * 저장·발행
 * 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface VoucherPoolReconciliationCommandService {
    /**
     * `run`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = run(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun run(command: ReconciliationCommand): MutationResult<ReconciliationProgressSnapshot>
    /**
     * `progress`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = progress(tenantId = "tenant-a", batchId = UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param batchId 요청 경계에서 검증된 `batchId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun progress(tenantId: String, batchId: UUID): ReconciliationProgressSnapshot?
}

/**
 * 공유 idempotency fence 뒤에서 durable batch-scoped reconciliation claim 하나를 생성합니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property repository 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property idempotency 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property claims 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Suppress("SqlResolve")
internal class JdbcVoucherPoolReconciliationCommandService(
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: VoucherPoolJdbcExecutor,
    /**
     * `repository`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: VoucherPoolRepository,
    /**
     * `idempotency`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val idempotency: VoucherPoolIdempotencyRepository,
    /**
     * `claims`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val claims: JdbcVoucherPoolWorkerRepository,
): VoucherPoolReconciliationCommandService {

    companion object: KLogging()

    /**
     * `run`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = run(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun run(command: ReconciliationCommand): MutationResult<ReconciliationProgressSnapshot> {
        val decision = executor.operatorTransaction {
            idempotency.acquire(
                scope = CommandScope(command.tenantId, OP_RECONCILIATION_RUN),
                rawKey = command.idempotencyKey,
                fingerprint = VoucherPoolFingerprint.command(
                    OP_RECONCILIATION_RUN,
                    mapOf("batchId" to command.batchId.toString())
                ),
            )
        }
        return when (decision) {
            is IdempotencyDecision.Execute    -> executeOwned(decision.owner, command)
            is IdempotencyDecision.Replay     -> MutationResult.Replay(decision.descriptor)
            is IdempotencyDecision.Expired    -> MutationResult.Expired(decision.effectId, decision.terminalCode)
            is IdempotencyDecision.InProgress -> fail(ReconciliationCommandFailure.COMMAND_IN_PROGRESS)
            IdempotencyDecision.FingerprintConflict -> fail(ReconciliationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT)
        }
    }

    /**
     * `progress`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = progress(tenantId = "tenant-a", batchId = UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param batchId 요청 경계에서 검증된 `batchId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun progress(tenantId: String, batchId: UUID): ReconciliationProgressSnapshot? =
        executor.operatorTransaction {
            selectProgress(currentConnection(), tenantId, batchId, forUpdate = false)
        }

    /**
     * executeOwned은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun executeOwned(
        owner: IdempotencyOwner,
        command: ReconciliationCommand,
    ): MutationResult<ReconciliationProgressSnapshot> = try {
        val progress = executor.operatorTransaction {
            val connection = currentConnection()
            idempotency.lockOwnerForExecution(owner)

            val campaignId = resolveCampaignId(command.tenantId, command.batchId)
                ?: fail(ReconciliationCommandFailure.SCOPE_NOT_FOUND)

            repository.lockCampaignForShare(command.tenantId, campaignId)
            repository.lockBatchForUpdate(command.tenantId, command.batchId)
                ?: fail(ReconciliationCommandFailure.SCOPE_NOT_FOUND)

            val scheduled = claims.scheduleReconciliationInTransaction(command.tenantId, command.batchId)
            if (!scheduled) fail(ReconciliationCommandFailure.COMMAND_IN_PROGRESS)

            val snapshot = checkNotNull(selectProgress(connection, command.tenantId, command.batchId, forUpdate = true))
            idempotency.finalize(
                owner = owner,
                descriptor = SafeResponseDescriptor.success(
                    HTTP_ACCEPTED,
                    "RECONCILIATION_ACCEPTED",
                    command.batchId,
                    snapshot.revision
                ),
                effect = EffectReference.effect(command.batchId),
            )
            snapshot
        }
        MutationResult.Applied(progress)
    } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
        releaseOwner(owner)
        throw failure
    }

    /**
     * releaseOwner은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    private fun releaseOwner(owner: IdempotencyOwner) {
        try {
            executor.operatorTransaction {
                idempotency.releaseRetryable(owner)
            }
        } catch (_: RuntimeException) {
            // command failure는 authoritative로 남고, owner lease는 안전하게 만료됩니다.
        }
    }

    /**
     * resolveCampaignId은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun resolveCampaignId(tenantId: String, batchId: UUID): UUID? =
        VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.campaignId)
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.batchId eq batchId)
            }
            .singleOrNull()
            ?.get(VoucherPoolBatchTable.campaignId)

    /**
     * selectProgress은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param forUpdate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun selectProgress(
        connection: Connection,
        tenantId: String,
        batchId: UUID,
        forUpdate: Boolean,
    ): ReconciliationProgressSnapshot? =
        // claim 전체 projection과 transaction snapshot 시각을 함께 읽고 선택적으로 row lock을 유지해야 합니다.
        connection.prepareStatement(
            "SELECT *,transaction_timestamp() AS observed_at FROM voucher_pool_worker_claims " +
                    "WHERE tenant_id=? AND worker_type='RECONCILIATION' AND scope_id=?" + if (forUpdate) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result ->
                if (result.next()) result.progressSnapshot() else null
            }
        }

    /**
     * progressSnapshot은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun ResultSet.progressSnapshot(): ReconciliationProgressSnapshot {
        val worker = WorkerClaimSnapshot(
            getString("tenant_id"),
            WorkerKind.valueOf(getString("worker_type")),
            getObject("scope_id", UUID::class.java),
            getString("owner_id"),
            getTimestamp("claim_until")?.toInstant(),
            getLong("cursor"),
            getInt("attempt"),
            getTimestamp("next_attempt_at").toInstant(),
            getLong("checkpoint"),
            getString("poison_reason"),
            getLong("revision"),
        )
        return ReconciliationProgressSnapshot(
            kind = worker.kind,
            scopeId = worker.scopeId,
            state = worker.state.name,
            cursor = worker.cursor,
            checkpoint = worker.checkpoint,
            attempt = worker.attempt,
            nextAction = worker.nextAction,
            revision = worker.revision,
            observedAt = getTimestamp("observed_at").toInstant(),
        )
    }

    /**
     * currentConnection은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun currentConnection(): Connection = TransactionManager.currentOrNull()
        .checkNotNull { "voucher pool reconciliation requires an active JDBC transaction" }
        .connection
        .connection as Connection
}

/**
 * fail은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun fail(reason: ReconciliationCommandFailure): Nothing = throw ReconciliationCommandException(reason)

/**
 * `HTTP_ACCEPTED`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val HTTP_ACCEPTED = 202
/**
 * `OP_RECONCILIATION_RUN`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val OP_RECONCILIATION_RUN = "reconciliation-run"
/**
 * `MAX_TENANT_LENGTH`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
 *
 * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
 */
private const val MAX_TENANT_LENGTH = 64
/**
 * `MAX_IDEMPOTENCY_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
