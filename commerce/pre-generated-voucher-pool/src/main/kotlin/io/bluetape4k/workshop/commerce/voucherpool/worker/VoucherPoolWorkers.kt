@file:Suppress("LargeClass", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.worker

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ExpectedReservation
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ExpectedUserLimit
import io.bluetape4k.workshop.commerce.voucherpool.persistence.LockedWorkerChain
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAllocationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCampaignTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolEntryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolDepthTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReservationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolUserLimitTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import io.bluetape4k.workshop.commerce.voucherpool.persistence.WorkerCandidate
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.time.Instant
import java.util.*

/**
 * WorkerChunkOutcome은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class WorkerChunkOutcome(
    /**
     * `claim`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val claim: WorkerClaim?,
    /**
     * `processed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val processed: Int,
    /**
     * `effects`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val effects: Int,
    /**
     * `completed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val completed: Boolean,
)

/**
 * WorkerRunState은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class WorkerRunState {
    /**
     * `WorkerRunState.COMPLETED`은 WorkerRunState 업무 상태의 `COMPLETED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
     * 분기를 선택하는
     * 기준으로 사용합니다.
     */
    COMPLETED,
    /**
     * `WorkerRunState.NOT_ACQUIRED`은 WorkerRunState 업무 상태의 `NOT_ACQUIRED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    NOT_ACQUIRED,
    /**
     * `WorkerRunState.CANCELLED`은 WorkerRunState 업무 상태의 `CANCELLED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
     * 분기를 선택하는
     * 기준으로 사용합니다.
     */
    CANCELLED,
    /**
     * `WorkerRunState.RETRYABLE`은 WorkerRunState 업무 상태의 `RETRYABLE` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
     * 분기를 선택하는
     * 기준으로 사용합니다.
     */
    RETRYABLE,
    /**
     * `WorkerRunState.POISONED`은 WorkerRunState 업무 상태의 `POISONED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를
     * 선택하는
     * 기준으로 사용합니다.
     */
    POISONED,
    /**
     * `WorkerRunState.LOST`은 WorkerRunState 업무 상태의 `LOST` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    LOST,
}

/**
 * WorkerRunOutcome은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class WorkerRunOutcome(
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: WorkerRunState,
    /**
     * `processed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val processed: Int,
    /**
     * `effects`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val effects: Int,
    /**
     * `claim`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val claim: WorkerClaimSnapshot?,
    /**
     * `reasonCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reasonCode: String? = null,
)

/**
 * WorkerRunRequest은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class WorkerRunRequest(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
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
     * `owner`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val owner: String,
    /**
     * `requestedLimit`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val requestedLimit: Int = DEFAULT_WORKER_LIMIT,
)

/**
 * VoucherPoolWorkers은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal interface VoucherPoolWorkers {
    /**
     * runChunk은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param requestedLimit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun runChunk(claim: WorkerClaim, requestedLimit: Int = DEFAULT_WORKER_LIMIT): WorkerChunkOutcome
    /**
     * run은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun run(
        request: WorkerRunRequest,
        continueRunning: () -> Boolean = { !Thread.currentThread().isInterrupted },
    ): WorkerRunOutcome

    /**
     * completeCampaignRevocation은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param campaignId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun completeCampaignRevocation(tenantId: String, campaignId: UUID): Boolean
}

/**
 * JdbcVoucherPoolWorkers은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class JdbcVoucherPoolWorkers(
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: VoucherPoolJdbcExecutor,
    /**
     * `claims`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val claims: JdbcVoucherPoolWorkerRepository,
    /**
     * `repository`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: VoucherPoolRepository,
): VoucherPoolWorkers {

    companion object: KLogging()

    @Suppress("ReturnCount")
    /**
     * run은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param request 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param continueRunning 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun run(request: WorkerRunRequest, continueRunning: () -> Boolean): WorkerRunOutcome {
        var activeClaim = claims.claim(request.tenantId, request.kind, request.scopeId, request.owner)
            ?: return WorkerRunOutcome(
                state = WorkerRunState.NOT_ACQUIRED,
                processed = 0,
                effects = 0,
                claim = claims.snapshot(request.tenantId, request.kind, request.scopeId),
                reasonCode = "CLAIM_NOT_ACQUIRED",
            )
        var processed = 0
        var effects = 0
        while (true) {
            if (!continueRunning()) {
                val released = claims.release(activeClaim)
                return WorkerRunOutcome(WorkerRunState.CANCELLED, processed, effects, released, "CANCELLED")
            }
            val chunk = try {
                runChunk(activeClaim, request.requestedLimit)
            } catch (failure: WorkerChunkExecutionException) {
                return failedRun(failure.claim, processed, effects, failure.reasonCode)
            } catch (failure: StaleWorkerClaimException) {
                return lostRun(activeClaim, processed, effects, failure.redactedReasonCode())
            } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
                return failedRun(activeClaim, processed, effects, failure.redactedReasonCode())
            }
            processed += chunk.processed
            effects += chunk.effects

            if (chunk.completed) {
                return WorkerRunOutcome(
                    WorkerRunState.COMPLETED,
                    processed,
                    effects,
                    claims.snapshot(request.tenantId, request.kind, request.scopeId),
                )
            }
            activeClaim = checkNotNull(chunk.claim)
            if (chunk.processed == 0 && chunk.effects == 0) {
                return failedRun(activeClaim, processed, effects, "RECONCILIATION_REQUIRED")
            }
        }
    }

    /**
     * runChunk은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param requestedLimit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun runChunk(claim: WorkerClaim, requestedLimit: Int): WorkerChunkOutcome {
        require(claim.kind != WorkerKind.PURGE) { "purge retention belongs to Task 12" }
        val limit = requestedLimit.coerceAtMost(maximumLimit(claim.kind))
        require(limit > 0)

        return when (claim.kind) {
            WorkerKind.RECONCILIATION -> runReconciliation(claim, limit)
            WorkerKind.CAMPAIGN_REVOKE -> runCampaignRevoke(claim, limit)
            else                      -> runEntryChunk(claim, limit)
        }
    }

    /**
     * runEntryChunk은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun runEntryChunk(claim: WorkerClaim, limit: Int): WorkerChunkOutcome {
        var activeClaim = prepareBatchState(claim)
        val candidates = scanCandidates(activeClaim, limit)
        if (candidates.isEmpty()) return finalizeIfComplete(activeClaim)

        var effects = 0
        candidates.forEach { candidate ->
            try {
                activeClaim = executor.workerTransaction {
                    val connection = currentConnection()
                    claims.requireCurrentInTransaction(connection, activeClaim)
                    val chain = repository.lockCanonicalChain(candidate.authority)
                    if (chain != null && apply(activeClaim.kind, chain)) effects++
                    claims.checkpointInTransaction(connection, activeClaim, candidate.cursorAfter)
                }
            } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
                throw WorkerChunkExecutionException(activeClaim, failure)
            }
        }
        return WorkerChunkOutcome(activeClaim, candidates.size, effects, completed = false)
    }

    /**
     * failedRun은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun failedRun(
        claim: WorkerClaim,
        processed: Int,
        effects: Int,
        reason: String = "WORKER_CHUNK_FAILED",
    ): WorkerRunOutcome = try {
        val failure = claims.fail(claim, reason)
        val state = if (failure.snapshot.state == WorkerClaimState.POISONED) {
            WorkerRunState.POISONED
        } else {
            WorkerRunState.RETRYABLE
        }
        WorkerRunOutcome(state, processed, effects, failure.snapshot, reason)
    } catch (stale: StaleWorkerClaimException) {
        lostRun(claim, processed, effects, stale.redactedReasonCode())
    }

    /**
     * lostRun은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun lostRun(
        claim: WorkerClaim,
        processed: Int,
        effects: Int,
        reasonCode: String,
    ): WorkerRunOutcome =
        WorkerRunOutcome(
            state = WorkerRunState.LOST,
            processed = processed,
            effects = effects,
            claim = claims.snapshot(claim.tenantId, claim.kind, claim.scopeId),
            reasonCode = reasonCode,
        )

    /**
     * completeCampaignRevocation은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param campaignId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun completeCampaignRevocation(tenantId: String, campaignId: UUID): Boolean =
        executor.workerTransaction {
            completeCampaignRevocationInTransaction(tenantId, campaignId)
        }

    /**
     * runCampaignRevoke은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun runCampaignRevoke(claim: WorkerClaim, limit: Int): WorkerChunkOutcome = executor.workerTransaction {
        val connection = currentConnection()
        claims.requireCurrentInTransaction(connection, claim)
        val campaign = lockCampaignScope(claim.tenantId, claim.scopeId)
        check(campaign.state in setOf(CampaignState.REVOKING, CampaignState.REVOKED))

        if (campaign.state == CampaignState.REVOKED) {
            claims.completeInTransaction(connection, claim)
            return@workerTransaction WorkerChunkOutcome(null, 0, 0, completed = true)
        }
        val inserted = claims.ensureBatchRevokeClaimsForCampaignInTransaction(
            tenantId = claim.tenantId,
            campaignId = claim.scopeId,
            limit = limit,
        )
        val renewed = claims.checkpointInTransaction(connection, claim, claim.cursor + inserted)
        val more = claims.hasUnscheduledBatchRevokesInTransaction(claim.tenantId, claim.scopeId)

        if (more) {
            WorkerChunkOutcome(renewed, inserted, inserted, completed = false)
        } else {
            completeCampaignRevocationInTransaction(claim.tenantId, claim.scopeId)
            claims.completeInTransaction(connection, renewed)
            WorkerChunkOutcome(null, inserted, inserted, completed = true)
        }
    }

    /**
     * runReconciliation은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun runReconciliation(claim: WorkerClaim, limit: Int): WorkerChunkOutcome = executor.workerTransaction {
        val connection = currentConnection()
        claims.requireCurrentInTransaction(connection, claim)
        val scope = lockBatchScope(claim)
        var effects = reconcilePoolDepth(claim)
        val users = lockUserLimitPage(claim, scope.campaignId, limit)
        users.forEach { user ->
            if (reconcileUserLimit(claim, scope.campaignId, user)) {
                effects++
            }
        }

        if (effects > 0) {
            repository.appendAudit(
                VoucherPoolAuditRecord(
                    tenantId = claim.tenantId,
                    campaignId = scope.campaignId,
                    aggregateType = "RECONCILIATION",
                    aggregateId = claim.scopeId,
                    revision = claim.checkpoint + 1,
                    policyVersion = scope.policyVersion,
                    actorType = "WORKER",
                    reasonCode = "RECONCILED",
                    beforeCount = effects.toLong(),
                    afterCount = 0L,
                ),
            )
        }
        val renewed = claims.checkpointInTransaction(connection, claim, claim.cursor + users.size)

        if (users.size < limit) {
            claims.completeInTransaction(connection, renewed)
            WorkerChunkOutcome(null, users.size, effects, completed = true)
        } else {
            WorkerChunkOutcome(renewed, users.size, effects, completed = false)
        }
    }

    /**
     * prepareBatchState은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun prepareBatchState(claim: WorkerClaim): WorkerClaim {
        val transition = when (claim.kind) {
            WorkerKind.BATCH_REVOKE -> BatchState.REVOKING
            WorkerKind.BATCH_EXPIRY -> BatchState.EXPIRING
            else -> return claim
        }
        val terminal = when (claim.kind) {
            WorkerKind.BATCH_REVOKE -> BatchState.REVOKED
            WorkerKind.BATCH_EXPIRY -> BatchState.EXPIRED
        }

        return executor.workerTransaction {
            val connection = currentConnection()
            claims.requireCurrentInTransaction(connection, claim)
            val scope = lockBatchScope(claim)
            if (scope.batchState != transition && scope.batchState != terminal) {
                if (transition == BatchState.EXPIRING) {
                    check(scope.expiresAt != null && scope.expiresAt <= transactionTime(connection)) {
                        "batch expiry is not due"
                    }
                }
                val allowed: Boolean = when (transition) {
                    BatchState.REVOKING ->
                        scope.batchState in setOf(
                            BatchState.STAGING,
                            BatchState.ACTIVE,
                            BatchState.PAUSED,
                            BatchState.FAILED_RETRYABLE,
                            BatchState.FAILED_TERMINAL,
                        )
                    BatchState.EXPIRING -> scope.batchState in setOf(BatchState.ACTIVE, BatchState.PAUSED)
                    else                -> false
                }
                check(allowed) { "batch cannot enter ${transition.name} from ${scope.batchState.name}" }
                updateBatchState(claim, scope.batchRevision, transition)
            }
            claims.checkpointInTransaction(connection, claim, claim.cursor)
        }
    }

    /**
     * scanCandidates은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun scanCandidates(claim: WorkerClaim, limit: Int): List<WorkerEntryCandidate> =
        executor.workerTransaction {
            val connection = currentConnection()
            claims.requireCurrentInTransaction(connection, claim)
            val afterCursor = queryCandidates(claim, claim.cursor, limit)
            if (afterCursor.isNotEmpty() || claim.cursor == 0L) afterCursor else queryCandidates(claim, 0L, limit)
        }

    /**
     * queryCandidates은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun queryCandidates(
        claim: WorkerClaim,
        cursor: Long,
        limit: Int,
    ): List<WorkerEntryCandidate> {
        val statePredicate = when (claim.kind) {
            WorkerKind.RESERVATION_EXPIRY ->
                (VoucherPoolEntryTable.state eq EntryState.RESERVED) and
                        (VoucherPoolEntryTable.reservationExpiresAt lessEq CurrentTimestamp)

            WorkerKind.ALLOCATION_EXPIRY ->
                (VoucherPoolEntryTable.state eq EntryState.ALLOCATED) and
                        (VoucherPoolEntryTable.allocationExpiresAt lessEq CurrentTimestamp)

            WorkerKind.BATCH_REVOKE, WorkerKind.BATCH_EXPIRY ->
                VoucherPoolEntryTable.state inList listOf(
                    EntryState.AVAILABLE,
                    EntryState.RESERVED,
                    EntryState.ALLOCATED,
                )

            else -> return emptyList()
        }
        val joined = VoucherPoolEntryTable
            .innerJoin(
                VoucherPoolCampaignTable,
                { VoucherPoolEntryTable.tenantId },
                { VoucherPoolCampaignTable.tenantId },
                additionalConstraint = { VoucherPoolEntryTable.campaignId eq VoucherPoolCampaignTable.campaignId },
            )
            .innerJoin(
                VoucherPoolBatchTable,
                { VoucherPoolEntryTable.tenantId },
                { VoucherPoolBatchTable.tenantId },
                additionalConstraint = {
                    (VoucherPoolEntryTable.batchId eq VoucherPoolBatchTable.batchId) and
                            (VoucherPoolEntryTable.campaignId eq VoucherPoolBatchTable.campaignId)
                },
            )
        return joined
            .select(
                VoucherPoolEntryTable.sourceOrdinal,
                VoucherPoolEntryTable.tenantId,
                VoucherPoolEntryTable.campaignId,
                VoucherPoolEntryTable.batchId,
                VoucherPoolEntryTable.entryId,
                VoucherPoolEntryTable.revision,
                VoucherPoolEntryTable.reservationId,
                VoucherPoolEntryTable.userDigest,
                VoucherPoolCampaignTable.revision,
                VoucherPoolBatchTable.revision,
            )
            .where {
                (VoucherPoolEntryTable.tenantId eq claim.tenantId) and
                        (VoucherPoolEntryTable.batchId eq claim.scopeId) and
                        (VoucherPoolEntryTable.sourceOrdinal greaterEq cursor) and
                        statePredicate
            }
            .orderBy(
                VoucherPoolEntryTable.sourceOrdinal to SortOrder.ASC,
                VoucherPoolEntryTable.entryId to SortOrder.ASC,
            )
            .limit(limit)
            .map { row ->
                val reservationId = row[VoucherPoolEntryTable.reservationId]
                val userDigest = row[VoucherPoolEntryTable.userDigest]
                val reservationRevision = reservationId?.let { id ->
                    VoucherPoolReservationTable
                        .select(VoucherPoolReservationTable.revision)
                        .where {
                            (VoucherPoolReservationTable.tenantId eq claim.tenantId) and
                                    (VoucherPoolReservationTable.reservationId eq id)
                        }
                        .singleOrNull()
                        ?.get(VoucherPoolReservationTable.revision)
                } ?: 0L
                val userLimitRevision = userDigest?.let { digest ->
                    VoucherPoolUserLimitTable
                        .select(VoucherPoolUserLimitTable.revision)
                        .where {
                            (VoucherPoolUserLimitTable.tenantId eq claim.tenantId) and
                                    (VoucherPoolUserLimitTable.campaignId eq row[VoucherPoolEntryTable.campaignId]) and
                                    (VoucherPoolUserLimitTable.userDigest eq digest)
                        }
                        .singleOrNull()
                        ?.get(VoucherPoolUserLimitTable.revision)
                } ?: 0L
                WorkerEntryCandidate(
                    authority = WorkerCandidate(
                        tenantId = row[VoucherPoolEntryTable.tenantId],
                        campaignId = row[VoucherPoolEntryTable.campaignId],
                        batchId = row[VoucherPoolEntryTable.batchId],
                        entryId = row[VoucherPoolEntryTable.entryId],
                        expectedCampaignRevision = row[VoucherPoolCampaignTable.revision],
                        expectedBatchRevision = row[VoucherPoolBatchTable.revision],
                        expectedEntryRevision = row[VoucherPoolEntryTable.revision],
                        userLimits = if (userDigest == null) emptyList() else listOf(
                            ExpectedUserLimit(
                                userDigest = DigestValue.of(userDigest),
                                expectedRevision = userLimitRevision,
                            ),
                        ),
                        reservations = if (reservationId == null) emptyList() else listOf(
                            ExpectedReservation(
                                reservationId = reservationId,
                                expectedRevision = reservationRevision,
                            ),
                        ),
                    ),
                    cursorAfter = row[VoucherPoolEntryTable.sourceOrdinal] + 1,
                )
            }
    }

    /**
     * apply은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun apply(
        kind: WorkerKind,
        chain: LockedWorkerChain,
    ): Boolean = when (kind) {
        WorkerKind.RESERVATION_EXPIRY -> repository.expireReservation(chain)
        WorkerKind.ALLOCATION_EXPIRY -> repository.terminalizeWorkerEntry(
            chain, EntryState.EXPIRED, "ALLOCATION_EXPIRED",
        )
        WorkerKind.BATCH_REVOKE      -> repository.terminalizeWorkerEntry(
            chain, EntryState.REVOKED, "BATCH_REVOKED",
        )
        WorkerKind.BATCH_EXPIRY      -> repository.terminalizeWorkerEntry(
            chain, EntryState.EXPIRED, "BATCH_EXPIRED",
        )
        else                         -> false
    }

    /**
     * finalizeIfComplete은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun finalizeIfComplete(claim: WorkerClaim): WorkerChunkOutcome =
        executor.workerTransaction {
            val connection = currentConnection()
            claims.requireCurrentInTransaction(connection, claim)
            if (claim.kind in setOf(WorkerKind.BATCH_REVOKE, WorkerKind.BATCH_EXPIRY)) {
                val scope = lockBatchScope(claim)
                val remaining = activeEntryCount(claim)
                if (remaining != 0L || !poolDepthMatches(claim)) {
                    return@workerTransaction WorkerChunkOutcome(claim, 0, 0, completed = false)
                }
                val terminal = if (claim.kind == WorkerKind.BATCH_REVOKE) BatchState.REVOKED else BatchState.EXPIRED
                if (scope.batchState != terminal) {
                    updateBatchState(claim, scope.batchRevision, terminal)
                    repository.appendAudit(
                        VoucherPoolAuditRecord(
                            claim.tenantId,
                            scope.campaignId,
                            "BATCH",
                            claim.scopeId,
                            scope.batchRevision + 1,
                            scope.policyVersion,
                            "WORKER",
                            terminal.name,
                        ),
                    )
                }
                if (claim.kind == WorkerKind.BATCH_REVOKE) {
                    completeCampaignRevocationInTransaction(claim.tenantId, scope.campaignId)
                }
            }
            claims.completeInTransaction(connection, claim)
            WorkerChunkOutcome(null, 0, 0, completed = true)
        }

    /**
     * completeCampaignRevocationInTransaction은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트
     * 처리의 일관성을
     * 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun completeCampaignRevocationInTransaction(
        tenantId: String,
        campaignId: UUID,
    ): Boolean {
        val campaign = lockCampaignScope(tenantId, campaignId)
        return when {
            campaign.state == CampaignState.REVOKED  -> true
            campaign.state != CampaignState.REVOKING -> false
            campaignRevocationRemaining(tenantId, campaignId) != 0L -> false
            else                                     -> {
                updateCampaignState(tenantId, campaignId, campaign.revision, CampaignState.REVOKED)
                appendCampaignAudit(tenantId, campaignId, campaign, CampaignState.REVOKED)
                true
            }
        }
    }

    /**
     * campaignRevocationRemaining은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을
     * 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param campaignId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun campaignRevocationRemaining(tenantId: String, campaignId: UUID): Long =
        VoucherPoolBatchTable
            .selectAll()
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.campaignId eq campaignId) and
                        (VoucherPoolBatchTable.state notInList listOf(BatchState.REVOKED, BatchState.EXPIRED))
            }
            .count()

    /**
     * lockBatchScope은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun lockBatchScope(claim: WorkerClaim): LockedBatchScope {
        val campaignId = VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.campaignId)
            .where {
                (VoucherPoolBatchTable.tenantId eq claim.tenantId) and
                        (VoucherPoolBatchTable.batchId eq claim.scopeId)
            }
            .single()
            .get(VoucherPoolBatchTable.campaignId)
        VoucherPoolCampaignTable
            .select(VoucherPoolCampaignTable.campaignId)
            .where {
                (VoucherPoolCampaignTable.tenantId eq claim.tenantId) and
                        (VoucherPoolCampaignTable.campaignId eq campaignId)
            }
            .forUpdate()
            .single()
        val batch = VoucherPoolBatchTable
            .selectAll()
            .where {
                (VoucherPoolBatchTable.tenantId eq claim.tenantId) and
                        (VoucherPoolBatchTable.batchId eq claim.scopeId)
            }
            .forUpdate()
            .single()
        return LockedBatchScope(
            campaignId = campaignId,
            batchState = batch[VoucherPoolBatchTable.state],
            batchRevision = batch[VoucherPoolBatchTable.revision],
            policyVersion = batch[VoucherPoolBatchTable.policyVersion],
            expiresAt = batch[VoucherPoolBatchTable.expiresAt],
        )
    }

    /**
     * updateBatchState은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun updateBatchState(
        claim: WorkerClaim,
        expectedRevision: Long,
        state: BatchState,
    ) {
        val updated = VoucherPoolBatchTable.update({
            (VoucherPoolBatchTable.tenantId eq claim.tenantId) and
                    (VoucherPoolBatchTable.batchId eq claim.scopeId) and
                    (VoucherPoolBatchTable.revision eq expectedRevision)
        }) {
            it[VoucherPoolBatchTable.state] = state
            it[VoucherPoolBatchTable.revision] = VoucherPoolBatchTable.revision + 1
        }
        check(updated == 1)
    }

    /**
     * activeEntryCount은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun activeEntryCount(claim: WorkerClaim): Long =
        VoucherPoolEntryTable
            .selectAll()
            .where {
                (VoucherPoolEntryTable.tenantId eq claim.tenantId) and
                        (VoucherPoolEntryTable.batchId eq claim.scopeId) and
                        (VoucherPoolEntryTable.state inList listOf(EntryState.AVAILABLE, EntryState.RESERVED, EntryState.ALLOCATED))
            }
            .count()

    /**
     * poolDepthMatches은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun poolDepthMatches(claim: WorkerClaim): Boolean {
        val actualCount = VoucherPoolEntryTable.entryId.count()
        val actual = VoucherPoolEntryTable
            .select(VoucherPoolEntryTable.state, actualCount)
            .where {
                (VoucherPoolEntryTable.tenantId eq claim.tenantId) and
                        (VoucherPoolEntryTable.batchId eq claim.scopeId)
            }
            .groupBy(VoucherPoolEntryTable.state)
            .associate { row -> row[VoucherPoolEntryTable.state] to row[actualCount] }
        val stored = VoucherPoolDepthTable
            .select(VoucherPoolDepthTable.state, VoucherPoolDepthTable.entryCount)
            .where {
                (VoucherPoolDepthTable.tenantId eq claim.tenantId) and
                        (VoucherPoolDepthTable.batchId eq claim.scopeId) and
                        (VoucherPoolDepthTable.entryCount neq 0L)
            }
            .associate { row -> row[VoucherPoolDepthTable.state] to row[VoucherPoolDepthTable.entryCount] }
        return actual == stored
    }

    /**
     * reconcilePoolDepth은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claim 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun reconcilePoolDepth(claim: WorkerClaim): Int {
        val depthRows = VoucherPoolDepthTable
            .select(
                VoucherPoolDepthTable.state,
                VoucherPoolDepthTable.entryCount,
                VoucherPoolDepthTable.revision,
            )
            .where {
                (VoucherPoolDepthTable.tenantId eq claim.tenantId) and
                        (VoucherPoolDepthTable.batchId eq claim.scopeId)
            }
            .orderBy(VoucherPoolDepthTable.state to SortOrder.ASC)
            .forUpdate()

        return depthRows.count { row ->
            val state = row[VoucherPoolDepthTable.state]
            val actual = VoucherPoolEntryTable
                .selectAll()
                .where {
                    (VoucherPoolEntryTable.tenantId eq claim.tenantId) and
                            (VoucherPoolEntryTable.batchId eq claim.scopeId) and
                            (VoucherPoolEntryTable.state eq state)
                }
                .count()
            if (row[VoucherPoolDepthTable.entryCount] == actual) return@count false

            val updated = VoucherPoolDepthTable.update({
                (VoucherPoolDepthTable.tenantId eq claim.tenantId) and
                        (VoucherPoolDepthTable.batchId eq claim.scopeId) and
                        (VoucherPoolDepthTable.state eq state) and
                        (VoucherPoolDepthTable.revision eq row[VoucherPoolDepthTable.revision])
            }) {
                it[VoucherPoolDepthTable.entryCount] = actual
                it[VoucherPoolDepthTable.revision] = VoucherPoolDepthTable.revision + 1
            }
            check(updated == 1)
            true
        }
    }

    /**
     * lockUserLimitPage은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun lockUserLimitPage(
        claim: WorkerClaim,
        campaignId: UUID,
        limit: Int,
    ): List<UserLimitProjection> =
        VoucherPoolUserLimitTable
            .selectAll()
            .where {
                (VoucherPoolUserLimitTable.tenantId eq claim.tenantId) and
                        (VoucherPoolUserLimitTable.campaignId eq campaignId)
            }
            .orderBy(VoucherPoolUserLimitTable.userDigest to SortOrder.ASC)
            .offset(claim.cursor)
            .limit(limit)
            .forUpdate()
            .map { row ->
                val digest = row[VoucherPoolUserLimitTable.userDigest]
                UserLimitProjection(
                    digest = digest,
                    storedReservations = row[VoucherPoolUserLimitTable.activeReservations],
                    storedAllocations = row[VoucherPoolUserLimitTable.activeAllocations],
                    storedLifetime = row[VoucherPoolUserLimitTable.lifetimeConsumed],
                    revision = row[VoucherPoolUserLimitTable.revision],
                    actualReservations = VoucherPoolReservationTable
                        .selectAll()
                        .where {
                            (VoucherPoolReservationTable.tenantId eq claim.tenantId) and
                                    (VoucherPoolReservationTable.campaignId eq campaignId) and
                                    (VoucherPoolReservationTable.userDigest eq digest) and
                                    (VoucherPoolReservationTable.state eq ReservationState.ACTIVE)
                        }
                        .count()
                        .toInt(),
                    actualAllocations = VoucherPoolEntryTable
                        .selectAll()
                        .where {
                            (VoucherPoolEntryTable.tenantId eq claim.tenantId) and
                                    (VoucherPoolEntryTable.campaignId eq campaignId) and
                                    (VoucherPoolEntryTable.userDigest eq digest) and
                                    (VoucherPoolEntryTable.state eq EntryState.ALLOCATED)
                        }
                        .count()
                        .toInt(),
                    actualLifetime = VoucherPoolAllocationTable
                        .selectAll()
                        .where {
                            (VoucherPoolAllocationTable.tenantId eq claim.tenantId) and
                                    (VoucherPoolAllocationTable.campaignId eq campaignId) and
                                    (VoucherPoolAllocationTable.userDigest eq digest) and
                                    (VoucherPoolAllocationTable.replacementOrdinal eq 0)
                        }
                        .count()
                        .toInt(),
                )
            }

    /**
     * reconcileUserLimit은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun reconcileUserLimit(
        claim: WorkerClaim,
        campaignId: UUID,
        user: UserLimitProjection,
    ): Boolean {
        if (user.matches()) return false
        val updated = VoucherPoolUserLimitTable.update({
            (VoucherPoolUserLimitTable.tenantId eq claim.tenantId) and
                    (VoucherPoolUserLimitTable.campaignId eq campaignId) and
                    (VoucherPoolUserLimitTable.userDigest eq user.digest) and
                    (VoucherPoolUserLimitTable.revision eq user.revision)
        }) {
            it[VoucherPoolUserLimitTable.activeReservations] = user.actualReservations
            it[VoucherPoolUserLimitTable.activeAllocations] = user.actualAllocations
            it[VoucherPoolUserLimitTable.lifetimeConsumed] = user.actualLifetime
            it[VoucherPoolUserLimitTable.revision] = VoucherPoolUserLimitTable.revision + 1
        }
        check(updated == 1)
        return true
    }

    /**
     * lockCampaignScope은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param campaignId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun lockCampaignScope(tenantId: String, campaignId: UUID): LockedCampaignScope =
        VoucherPoolCampaignTable
            .select(
                VoucherPoolCampaignTable.state,
                VoucherPoolCampaignTable.revision,
                VoucherPoolCampaignTable.policyVersion,
            )
            .where {
                (VoucherPoolCampaignTable.tenantId eq tenantId) and
                        (VoucherPoolCampaignTable.campaignId eq campaignId)
            }
            .forUpdate()
            .single()
            .let { row ->
                LockedCampaignScope(
                    state = row[VoucherPoolCampaignTable.state],
                    revision = row[VoucherPoolCampaignTable.revision],
                    policyVersion = row[VoucherPoolCampaignTable.policyVersion],
                )
            }

    /**
     * updateCampaignState은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun updateCampaignState(
        tenantId: String,
        campaignId: UUID,
        expectedRevision: Long,
        state: CampaignState,
    ) {
        val updated = VoucherPoolCampaignTable.update({
            (VoucherPoolCampaignTable.tenantId eq tenantId) and
                    (VoucherPoolCampaignTable.campaignId eq campaignId) and
                    (VoucherPoolCampaignTable.revision eq expectedRevision)
        }) {
            it[VoucherPoolCampaignTable.state] = state
            it[VoucherPoolCampaignTable.revision] = VoucherPoolCampaignTable.revision + 1
        }
        check(updated == 1)
    }

    /**
     * appendCampaignAudit은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun appendCampaignAudit(
        tenantId: String,
        campaignId: UUID,
        campaign: LockedCampaignScope,
        state: CampaignState,
    ) {
        repository.appendAudit(
            VoucherPoolAuditRecord(
                tenantId,
                campaignId,
                "CAMPAIGN",
                campaignId,
                campaign.revision + 1,
                campaign.policyVersion,
                "WORKER",
                state.name,
            ),
        )
    }

    /**
     * maximumLimit은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param kind 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun maximumLimit(kind: WorkerKind): Int =
        if (kind in setOf(WorkerKind.RECONCILIATION, WorkerKind.CAMPAIGN_REVOKE)) 50 else 100

    /**
     * currentConnection은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun currentConnection(): Connection =
        TransactionManager.currentOrNull()
            ?.connection
            ?.connection as? Connection ?: error("voucher pool worker repository requires an active JDBC transaction")

    /** worker entry transition과 같은 PostgreSQL transaction snapshot을 사용해야 하므로 raw timestamp 조회를 유지합니다. */
    /** worker entry transition과 같은 PostgreSQL transaction snapshot을 사용해야 하므로 raw timestamp 조회를 유지합니다. */
    private fun transactionTime(connection: Connection): Instant =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT transaction_timestamp()").use { result ->
                result.next()
                result.getTimestamp(1).toInstant()
            }
        }
}

/**
 * WorkerEntryCandidate은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
private data class WorkerEntryCandidate(val authority: WorkerCandidate, val cursorAfter: Long)

/**
 * WorkerChunkExecutionException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
private class WorkerChunkExecutionException(
    /**
     * `claim`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val claim: WorkerClaim,
    cause: RuntimeException,
): RuntimeException("worker chunk failed", cause) {
    /**
     * `reasonCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reasonCode: String = cause.redactedReasonCode()
}

/**
 * redactedReasonCode은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun RuntimeException.redactedReasonCode(): String = when (this) {
    is StaleWorkerClaimException -> "STALE_CLAIM"
    is VoucherPoolJdbcTimeoutException -> "WORKER_TIMEOUT"
    else                         -> "WORKER_CHUNK_FAILED"
}

/**
 * LockedBatchScope은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
private data class LockedBatchScope(
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `batchState`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val batchState: BatchState,
    /**
     * `batchRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val batchRevision: Long,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant?,
)

/**
 * LockedCampaignScope은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
private data class LockedCampaignScope(
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: CampaignState,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
)

/**
 * UserLimitProjection은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
private data class UserLimitProjection(
    /**
     * `digest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val digest: ByteArray,
    /**
     * `storedReservations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val storedReservations: Int,
    /**
     * `storedAllocations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val storedAllocations: Int,
    /**
     * `storedLifetime`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val storedLifetime: Int,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `actualReservations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val actualReservations: Int,
    /**
     * `actualAllocations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val actualAllocations: Int,
    /**
     * `actualLifetime`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val actualLifetime: Int,
) {
    /**
     * matches은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun matches(): Boolean =
        storedReservations == actualReservations &&
                storedAllocations == actualAllocations &&
                storedLifetime == actualLifetime
}

/**
 * `DEFAULT_WORKER_LIMIT`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
internal const val DEFAULT_WORKER_LIMIT = 100
