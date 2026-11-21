package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.api.EtaConfidence
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.QueueProjection
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobLease
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.domain.JobTransitions
import io.bluetape4k.workshop.operations.jobconsole.queue.QueueProjectionService
import io.bluetape4k.workshop.operations.jobconsole.queue.QueueRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.sql.DataSource

/**
 * DemoCallerScope은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
data class DemoCallerScope(
    /**
     * `tenantId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `submitterHash`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val submitterHash: String,
) {
    init {
        require(tenantId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid tenant scope" }
        require(submitterHash.isNotBlank()) { "submitter hash must not be blank" }
    }
}

/**
 * SubmitJobResult은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
data class SubmitJobResult(
    /**
     * `jobId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val jobId: UUID,
    /**
     * `enqueueSequence`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val enqueueSequence: Long,
    /**
     * `replayed`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean,
)

/**
 * InsertedSubmittedJob은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class InsertedSubmittedJob(
    /**
     * `jobId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val jobId: UUID,
    /**
     * `enqueueSequence`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val enqueueSequence: Long,
)

/**
 * ClaimedJob은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
data class ClaimedJob(
    /**
     * `lease`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val lease: JobLease,
    /**
     * `tenantId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `jobType`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val jobType: JobType,
    /**
     * `failureMode`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val failureMode: FailureMode,
    /**
     * `workUnits`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val workUnits: Int,
    /**
     * `enqueueSequence`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val enqueueSequence: Long,
    /**
     * `completedChunk`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val completedChunk: Long,
)

/**
 * CheckpointResult은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
data class CheckpointResult(
    /**
     * `lease`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val lease: JobLease,
    /**
     * `completedChunk`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val completedChunk: Long,
    /**
     * `progress`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val progress: Int,
    /**
     * `state`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: JobState,
)

/**
 * StoredJob은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
data class StoredJob(
    /**
     * `jobId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val jobId: UUID,
    /**
     * `tenantId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `submitterHash`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val submitterHash: String,
    /**
     * `jobType`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val jobType: JobType,
    /**
     * `state`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: JobState,
    /**
     * `progress`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val progress: Int,
    /**
     * `completedChunk`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val completedChunk: Long,
    /**
     * `enqueueSequence`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val enqueueSequence: Long,
    /**
     * `version`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: Long,
    /**
     * `updatedAt`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val updatedAt: Instant,
)

/**
 * CancelJobResult은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
data class CancelJobResult(
    /**
     * `jobId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val jobId: UUID,
    /**
     * `state`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: JobState,
    /**
     * `notificationRequired`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val notificationRequired: Boolean,
)

/**
 * JobRepositoryException은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class JobRepositoryException(
    /**
     * `code`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: JobProblemCode,
): RuntimeException(code.name)

/**
 * JobRepository은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class JobRepository(
    /**
     * `dataSource`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    internal val dataSource: DataSource,
) : AutoCloseable {

    /**
     * `jdbc`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc = JobJdbcExecutor(dataSource)

    /** Exposed database manager를 애플리케이션 lifecycle과 함께 해제합니다. */
    /** Exposed database manager를 애플리케이션 lifecycle과 함께 해제합니다. */
    override fun close() {
        jdbc.close()
    }

    companion object: KLogging() {
        /**
         * `MAX_DURATION_SAMPLES`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val MAX_DURATION_SAMPLES: Int = 100
    }

    /**
     * runnableTenantIds은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun runnableTenantIds(limit: Int): List<String> {
        limit.requireInRange(1, 100, "limit")

        return jdbc.exposedTransaction {
            val oldestUpdatedAt = JobTable.updatedAt.min()
            JobTable
                .select(JobTable.tenantId, oldestUpdatedAt)
                .where {
                    (JobTable.state eq JobState.QUEUED.wireValue) or
                        ((JobTable.state inList listOf(JobState.RUNNING.wireValue, JobState.CANCEL_REQUESTED.wireValue)) and
                            (JobTable.leaseExpiresAt lessEq CurrentTimestamp))
                }
                .groupBy(JobTable.tenantId)
                .orderBy(
                    oldestUpdatedAt to SortOrder.ASC,
                    JobTable.tenantId to SortOrder.ASC,
                )
                .limit(limit)
                .map { it[JobTable.tenantId] }
        }
    }

    /** 애플리케이션 테이블에 의존하지 않는 connection 생존성 확인입니다. */
    /** 애플리케이션 테이블에 의존하지 않는 connection 생존성 확인입니다. */
    fun databaseReady(): Boolean = runCatching {
        jdbc.exposedTransaction { select(CurrentTimestamp).singleOrNull() != null }
    }.getOrDefault(false)

    /**
     * submit은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun submit(
        scope: DemoCallerScope,
        idempotencyKey: String,
        request: SubmitJobRequest,
        now: Instant,
    ): SubmitJobResult {
        idempotencyKey.requireNotBlank("idempotencyKey")
        request.workUnits.requireInRange(1, 10_000, "request.workUnits")

        val keyHash = sha256("${scope.tenantId}:${scope.submitterHash}:$idempotencyKey")
        val fingerprint = sha256("${request.jobType.wireValue}:${request.workUnits}:${request.failureMode.wireValue}")

        return jdbc.transaction { connection ->
            advisoryLock(connection, "request:$keyHash")
            val existing =
                JobRequestTable
                    .select(JobRequestTable.requestFingerprint, JobRequestTable.jobId)
                    .where {
                        (JobRequestTable.tenantId eq scope.tenantId) and
                            (JobRequestTable.submitterHash eq normalizedSubmitterHash(scope.submitterHash)) and
                            (JobRequestTable.keyHash eq keyHash)
                    }
                    .singleOrNull()
                    ?.let {
                        ExistingRequest(
                            fingerprint = it[JobRequestTable.requestFingerprint].trim(),
                            jobId = it[JobRequestTable.jobId],
                        )
                    }
            existing?.let { existing ->
                if (existing.fingerprint != fingerprint) {
                    throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_KEY_REUSED)
                }
                return@transaction SubmitJobResult(
                    existing.jobId,
                    enqueueSequenceDsl(existing.jobId),
                    replayed = true,
                )
            }

            val jobId = Uuid.V7.nextId()
            // MAX(enqueue_sequence)+1은 tenant별 queue lock 안에서만 읽고 삽입해야 합니다.
            // legacy 경로도 bounded idempotency 경로와 같은 PostgreSQL 직렬화 경계를 사용합니다.
            advisoryLock(connection, "queue:${scope.tenantId}")
            val sequence = nextEnqueueSequenceDsl(scope.tenantId)
            insertJobDsl(scope, request, jobId, sequence, now)
            insertRequestDsl(scope, keyHash, fingerprint, jobId, now)
            insertOutboxDsl(jobId, now)
            insertHistoryDsl(jobId, now)
            SubmitJobResult(jobId, sequence, replayed = false)
        }
    }

    /**
     * insertSubmittedJob은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    internal fun insertSubmittedJob(
        connection: Connection,
        scope: DemoCallerScope,
        request: SubmitJobRequest,
        jobId: UUID,
        now: Instant,
    ): InsertedSubmittedJob {
        advisoryLock(connection, "queue:${scope.tenantId}")
        val sequence = nextEnqueueSequenceDsl(scope.tenantId)
        insertJobDsl(scope, request, jobId, sequence, now)
        insertOutboxDsl(jobId, now)
        insertHistoryDsl(jobId, now)
        return InsertedSubmittedJob(jobId, sequence)
    }

    /**
     * nextEnqueueSequenceDsl은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun nextEnqueueSequenceDsl(tenantId: String): Long {
        val maximum = JobTable.enqueueSequence.max()
        return (JobTable
            .select(maximum)
            .where { JobTable.tenantId eq tenantId }
            .singleOrNull()
            ?.get(maximum) ?: 0L) + 1L
    }

    /**
     * enqueueSequenceDsl은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param jobId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun enqueueSequenceDsl(jobId: UUID): Long =
        JobTable
            .select(JobTable.enqueueSequence)
            .where { JobTable.jobId eq jobId }
            .single()[JobTable.enqueueSequence]

    /**
     * insertJobDsl은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun insertJobDsl(
        scope: DemoCallerScope,
        request: SubmitJobRequest,
        jobId: UUID,
        sequence: Long,
        now: Instant,
    ) {
        JobTable.insert { statement ->
            statement[JobTable.jobId] = jobId
            statement[JobTable.tenantId] = scope.tenantId
            statement[JobTable.submitterHash] = normalizedSubmitterHash(scope.submitterHash)
            statement[JobTable.jobType] = request.jobType.wireValue
            statement[JobTable.workUnits] = request.workUnits
            statement[JobTable.failureMode] = request.failureMode.wireValue
            statement[JobTable.enqueueSequence] = sequence
            statement[JobTable.state] = JobState.QUEUED.wireValue
            statement[JobTable.createdAt] = now
            statement[JobTable.updatedAt] = now
        }
    }

    /**
     * insertRequestDsl은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun insertRequestDsl(
        scope: DemoCallerScope,
        keyHash: String,
        fingerprint: String,
        jobId: UUID,
        now: Instant,
    ) {
        JobRequestTable.insert { statement ->
            statement[JobRequestTable.tenantId] = scope.tenantId
            statement[JobRequestTable.submitterHash] = normalizedSubmitterHash(scope.submitterHash)
            statement[JobRequestTable.keyHash] = keyHash
            statement[JobRequestTable.requestFingerprint] = fingerprint
            statement[JobRequestTable.jobId] = jobId
            statement[JobRequestTable.createdAt] = now
            statement[JobRequestTable.state] = "TERMINAL"
            statement[JobRequestTable.generation] = 1L
            statement[JobRequestTable.updatedAt] = now
        }
    }

    /**
     * insertOutboxDsl은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param jobId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun insertOutboxDsl(jobId: UUID, now: Instant) {
        listOf("job.updated", "queue.updated").forEach { eventType ->
            JobOutboxTable.insert { statement ->
                statement[JobOutboxTable.eventId] = Uuid.V7.nextId()
                statement[JobOutboxTable.jobId] = jobId
                statement[JobOutboxTable.eventType] = eventType
                statement[JobOutboxTable.resourceVersion] = 1L
                statement[JobOutboxTable.queueVersion] = 1L
                statement[JobOutboxTable.occurredAt] = now
            }
        }
    }

    /**
     * insertHistoryDsl은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param jobId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun insertHistoryDsl(jobId: UUID, now: Instant) {
        JobHistoryTable.insert { statement ->
            statement[JobHistoryTable.historyId] = Uuid.V7.nextId()
            statement[JobHistoryTable.jobId] = jobId
            statement[JobHistoryTable.fromState] = null
            statement[JobHistoryTable.toState] = JobState.QUEUED.wireValue
            statement[JobHistoryTable.reasonCode] = "submitted"
            statement[JobHistoryTable.resourceVersion] = 1L
            statement[JobHistoryTable.occurredAt] = now
        }
    }

    /** PostgreSQL 제어 경로: advisory lock과 `FOR UPDATE SKIP LOCKED`로 lease 공정성을 보장합니다. */
    /** PostgreSQL 제어 경로: advisory lock과 `FOR UPDATE SKIP LOCKED`로 lease 공정성을 보장합니다. */
    fun claimNext(tenantId: String, leaseDuration: Duration): ClaimedJob? = inTransaction { connection ->
        advisoryLock(connection, "claim:$tenantId")
        if (hasActiveLease(tenantId)) return@inTransaction null
        val candidate = connection.prepareStatement(
            """
                    SELECT job_id, job_type, failure_mode, work_units, enqueue_sequence, version
                    FROM jobs
                    WHERE tenant_id = ? AND state = ?
                    ORDER BY enqueue_sequence
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, JobState.QUEUED.wireValue)
            statement.executeQuery().use { result -> if (result.next()) ClaimCandidate.from(result) else null }
        } ?: return@inTransaction null
        activateLease(connection, tenantId, candidate, leaseDuration, JobState.QUEUED)
    }

    /** PostgreSQL row lock과 database clock으로 만료 lease를 직렬화하는 제어 경로입니다. */
    /** PostgreSQL row lock과 database clock으로 만료 lease를 직렬화하는 제어 경로입니다. */
    fun reclaimExpired(tenantId: String, leaseDuration: Duration): ClaimedJob? =
        inTransaction { connection ->
            advisoryLock(connection, "claim:$tenantId")
            if (cancelExpiredLease(connection, tenantId)) return@inTransaction null

            /**
             * `candidate`은 작업 운영 콘솔에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val candidate = connection.prepareStatement(
                """
                    SELECT job_id, job_type, failure_mode, work_units, enqueue_sequence, version
                    FROM jobs
                    WHERE tenant_id = ?
                      AND state = ?
                      AND lease_expires_at <= CURRENT_TIMESTAMP
                    ORDER BY enqueue_sequence
                    FOR UPDATE
                    LIMIT 1
                    """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.setString(2, JobState.RUNNING.wireValue)
                statement.executeQuery().use { result -> if (result.next()) ClaimCandidate.from(result) else null }
            } ?: return@inTransaction null
            activateLease(connection, tenantId, candidate, leaseDuration, null)
        }

    /** PostgreSQL 제어 경로: CAS update는 `RETURNING`과 interval 식을 하나의 원자 연산으로 사용합니다. */
    /** PostgreSQL 제어 경로: CAS update는 `RETURNING`과 interval 식을 하나의 원자 연산으로 사용합니다. */
    fun checkpoint(
        lease: JobLease,
        completedChunk: Long,
        progress: Int,
        renewalDuration: Duration = Duration.ofSeconds(30),
    ): CheckpointResult =
        inTransaction { connection ->
            require(progress in 0..100) { "progress must be between 0 and 100" }
            require(!renewalDuration.isNegative && !renewalDuration.isZero) { "renewal duration must be positive" }
            /**
             * `leaseState`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
             *
             * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
             */
            val leaseState = currentLeaseState(connection, lease)
            /**
             * `state`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
             *
             * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
             */
            val state = leaseState.state
            /**
             * `effectiveLease`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
             *
             * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
             */
            val effectiveLease = lease.copy(revision = leaseState.version)
            /**
             * `nextState`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
             *
             * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
             */
            val nextState = if (state == JobState.CANCEL_REQUESTED) {
                JobTransitions.next(state, JobSignal.CHECKPOINT)
            } else {
                state
            }
            /**
             * `updated`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val updated = connection.prepareStatement(
                """
                    UPDATE jobs
                    SET state = ?, progress = ?, version = version + 1, queue_version = queue_version + 1,
                        updated_at = CURRENT_TIMESTAMP,
                        lease_token = CASE WHEN ? THEN NULL ELSE lease_token END,
                        lease_expires_at = CASE WHEN ? THEN NULL
                                                ELSE CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond') END
                    WHERE job_id = ? AND lease_token = ? AND version = ?
                      AND lease_expires_at > CURRENT_TIMESTAMP
                    RETURNING version, lease_expires_at
                    """.trimIndent(),
            ).use { statement ->
                /**
                 * `terminal`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val terminal = nextState.terminal
                statement.setString(1, nextState.wireValue)
                statement.setInt(2, progress)
                statement.setBoolean(3, terminal)
                statement.setBoolean(4, terminal)
                statement.setLong(5, renewalDuration.toMillis())
                statement.setObject(6, effectiveLease.jobId)
                statement.setObject(7, effectiveLease.token)
                statement.setLong(8, effectiveLease.revision)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                    /**
                     * `expiresAt`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
                     *
                     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
                     */
                    val expiresAt = result.getTimestamp("lease_expires_at")?.toInstant() ?: lease.expiresAt
                    effectiveLease.copy(revision = result.getLong("version"), expiresAt = expiresAt)
                }
            }
            upsertCheckpoint(connection, lease.jobId, lease.token, completedChunk, progress)
            appendTransition(connection, lease.jobId, state, nextState, "checkpoint", updated.revision)
            CheckpointResult(updated, completedChunk, progress, nextState)
        }

    /** PostgreSQL 제어 경로: lease CAS와 `RETURNING`은 하나의 statement로 유지해야 합니다. */
    /** PostgreSQL 제어 경로: lease CAS와 `RETURNING`은 하나의 statement로 유지해야 합니다. */
    fun complete(lease: JobLease, signal: JobSignal): StoredJob =
        inTransaction { connection ->
            /**
             * `leaseState`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
             *
             * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
             */
            val leaseState = currentLeaseState(connection, lease)
            /**
             * `effectiveLease`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
             *
             * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
             */
            val effectiveLease = lease.copy(revision = leaseState.version)
            /**
             * `current`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val current = leaseState.state
            /**
             * `effectiveSignal`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val effectiveSignal =
                if (signal == JobSignal.RETRYABLE_FAILURE && leaseState.retryBudget == 0) {
                    JobSignal.RETRY_EXHAUSTED
                } else {
                    signal
                }
            /**
             * `next`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val next = JobTransitions.next(current, effectiveSignal)
            /**
             * `progress`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val progress =
                if (next == JobState.SUCCEEDED) {
                    100
                } else {
                    loadInCurrentTransaction(lease.jobId)?.progress ?: 0
                }
            /**
             * `version`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
             *
             * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
             */
            val version =
                connection.prepareStatement(
                    """
                    UPDATE jobs
                    SET state = ?, progress = ?,
                        retry_budget = CASE WHEN ? THEN retry_budget - 1 ELSE retry_budget END,
                        version = version + 1, queue_version = queue_version + 1,
                        lease_token = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE job_id = ? AND lease_token = ? AND version = ?
                    RETURNING version
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, next.wireValue)
                    statement.setInt(2, progress)
                    statement.setBoolean(3, effectiveSignal == JobSignal.RETRYABLE_FAILURE)
                    statement.setObject(4, effectiveLease.jobId)
                    statement.setObject(5, effectiveLease.token)
                    statement.setLong(6, effectiveLease.revision)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                        result.getLong(1)
                    }
                }
            appendTransition(connection, effectiveLease.jobId, current, next, effectiveSignal.name.lowercase(), version)
            requireNotNull(loadInCurrentTransaction(effectiveLease.jobId))
        }

    /**
     * load은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param jobId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun load(jobId: UUID): StoredJob? = jdbc.exposedTransaction {
        val job = JobTable
            .selectAll()
            .where { JobTable.jobId eq jobId }
            .singleOrNull()
            ?: return@exposedTransaction null
        val completedChunk = JobCheckpointTable
            .select(JobCheckpointTable.completedChunk)
            .where { JobCheckpointTable.jobId eq jobId }
            .singleOrNull()
            ?.get(JobCheckpointTable.completedChunk)
            ?: 0L
        job.toStoredJob(completedChunk)
    }

    /**
     * load은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param jobId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun load(scope: DemoCallerScope, jobId: UUID): StoredJob {
        val stored = load(jobId) ?: throw JobRepositoryException(JobProblemCode.JOB_NOT_FOUND)
        if (stored.tenantId != scope.tenantId || stored.submitterHash != normalizedSubmitterHash(scope.submitterHash)) {
            throw JobRepositoryException(JobProblemCode.SCOPE_DENIED)
        }
        return stored
    }

    /**
     * loadTenantJobs은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun loadTenantJobs(tenantId: String): List<StoredJob> = jdbc.exposedTransaction {
        // checkpoint를 job별로 다시 조회하지 않고 한 번의 LEFT JOIN으로 읽습니다.
        JobTable
            .leftJoin(JobCheckpointTable, { JobTable.jobId }, { JobCheckpointTable.jobId })
            .selectAll()
            .where { JobTable.tenantId eq tenantId }
            .orderBy(JobTable.enqueueSequence to SortOrder.ASC)
            .map { job -> job.toStoredJob(job.getOrNull(JobCheckpointTable.completedChunk) ?: 0L) }
    }

    /** PostgreSQL 제어 경로: row lock과 CAS `RETURNING`으로 cancellation 경쟁을 보호합니다. */
    /** PostgreSQL 제어 경로: row lock과 CAS `RETURNING`으로 cancellation 경쟁을 보호합니다. */
    fun cancel(scope: DemoCallerScope, jobId: UUID): CancelJobResult = inTransaction { connection ->
        val row =
            connection.prepareStatement(
                "SELECT tenant_id, submitter_hash, state, version FROM jobs WHERE job_id = ? FOR UPDATE",
            ).use { statement ->
                statement.setObject(1, jobId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw JobRepositoryException(JobProblemCode.JOB_NOT_FOUND)
                    CancelRow(
                        tenantId = result.getString("tenant_id"),
                        submitterHash = result.getString("submitter_hash").trim(),
                        state = JobState.entries.single { it.wireValue == result.getString("state") },
                        version = result.getLong("version"),
                    )
                }
            }
        if (row.tenantId != scope.tenantId || row.submitterHash != normalizedSubmitterHash(scope.submitterHash)) {
            throw JobRepositoryException(JobProblemCode.SCOPE_DENIED)
        }
        if (row.state.terminal || row.state == JobState.CANCEL_REQUESTED) {
            return@inTransaction CancelJobResult(jobId, row.state, row.state == JobState.CANCEL_REQUESTED)
        }

        val next = JobTransitions.next(row.state, JobSignal.CANCEL)
        val version = connection.prepareStatement(
            """
                    UPDATE jobs
                    SET state = ?, version = version + 1, queue_version = queue_version + 1,
                        updated_at = CURRENT_TIMESTAMP,
                        lease_token = CASE WHEN ? THEN NULL ELSE lease_token END,
                        lease_expires_at = CASE WHEN ? THEN NULL ELSE lease_expires_at END
                    WHERE job_id = ? AND version = ?
                    RETURNING version
                    """.trimIndent(),
        ).use { statement ->
            statement.setString(1, next.wireValue)
            statement.setBoolean(2, next.terminal)
            statement.setBoolean(3, next.terminal)
            statement.setObject(4, jobId)
            statement.setLong(5, row.version)
            statement.executeQuery().use { result ->
                if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                result.getLong("version")
            }
        }
        appendTransition(connection, jobId, row.state, next, "cancel_requested", version)
        CancelJobResult(jobId, next, next == JobState.CANCEL_REQUESTED)
    }

    /**
     * queueRows은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param afterSequence 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun queueRows(tenantId: String, afterSequence: Long?, limit: Int): List<QueueRow> =
        queryQueueRows(tenantId, afterSequence, limit.coerceIn(1, QueueProjectionService.MAX_PAGE_SIZE))

    /**
     * queuePageRows은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param afterSequence 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param pageSize 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun queuePageRows(tenantId: String, afterSequence: Long?, pageSize: Int): List<QueueRow> =
        queryQueueRows(
            tenantId,
            afterSequence,
            pageSize.coerceIn(1, QueueProjectionService.MAX_PAGE_SIZE) + 1,
        )

    /**
     * queryQueueRows은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param afterSequence 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param boundedLimit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun queryQueueRows(tenantId: String, afterSequence: Long?, boundedLimit: Int): List<QueueRow> {
        return jdbc.exposedTransaction {
            JobTable
                .select(
                    JobTable.jobId,
                    JobTable.jobType,
                    JobTable.state,
                    JobTable.enqueueSequence,
                    JobTable.queueVersion,
                    JobTable.updatedAt,
                )
                .where {
                    (JobTable.tenantId eq tenantId) and
                        (JobTable.state inList listOf(
                            JobState.QUEUED.wireValue,
                            JobState.RUNNING.wireValue,
                            JobState.CANCEL_REQUESTED.wireValue,
                        )) and
                        (afterSequence?.let { JobTable.enqueueSequence greater it } ?: Op.TRUE)
                }
                .orderBy(JobTable.enqueueSequence to SortOrder.ASC)
                .limit(boundedLimit)
                .map { it.toQueueRow() }
        }
    }

    /**
     * queueProjection은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param jobId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun queueProjection(tenantId: String, jobId: UUID, now: Instant): QueueProjection? =
        jdbc.exposedTransaction {
            /**
             * `activeStates`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
             *
             * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
             */
            val activeStates = listOf(
                JobState.QUEUED.wireValue,
                JobState.RUNNING.wireValue,
                JobState.CANCEL_REQUESTED.wireValue,
            )
            /**
             * `target`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val target = JobTable
                .select(JobTable.enqueueSequence, JobTable.queueVersion)
                .where {
                    (JobTable.tenantId eq tenantId) and
                        (JobTable.jobId eq jobId) and
                        (JobTable.state inList activeStates)
                }
                .singleOrNull()
                ?: return@exposedTransaction null
            /**
             * `targetSequence`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
             *
             * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
             */
            val targetSequence = target[JobTable.enqueueSequence]
            /**
             * `jobsAhead`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val jobsAhead = JobTable
                .select(JobTable.jobId)
                .where {
                    (JobTable.tenantId eq tenantId) and
                        (JobTable.enqueueSequence less targetSequence) and
                        (JobTable.state inList activeStates)
                }
                .count()
                .toInt()
            QueueProjection(
                position = jobsAhead + 1,
                jobsAhead = jobsAhead,
                estimatedStartRange = null,
                estimatedCompletionRange = null,
                confidence = EtaConfidence.INSUFFICIENT_DATA,
                sampleSize = 0,
                queueVersion = target[JobTable.queueVersion],
                updatedAt = now,
            )
        }

    /**
     * submitterQueuePageRows은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param scope 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param afterSequence 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param pageSize 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun submitterQueuePageRows(scope: DemoCallerScope, afterSequence: Long?, pageSize: Int): List<QueueRow> =
        jdbc.exposedTransaction {
            JobTable
                .select(
                    JobTable.jobId,
                    JobTable.jobType,
                    JobTable.state,
                    JobTable.enqueueSequence,
                    JobTable.queueVersion,
                    JobTable.updatedAt,
                )
                .where {
                    (JobTable.tenantId eq scope.tenantId) and
                        (JobTable.submitterHash eq normalizedSubmitterHash(scope.submitterHash)) and
                        (JobTable.state inList listOf(
                            JobState.QUEUED.wireValue,
                            JobState.RUNNING.wireValue,
                            JobState.CANCEL_REQUESTED.wireValue,
                        )) and
                        (afterSequence?.let { JobTable.enqueueSequence greater it } ?: Op.TRUE)
                }
                .orderBy(JobTable.enqueueSequence to SortOrder.ASC)
                .limit(pageSize.coerceIn(1, QueueProjectionService.MAX_PAGE_SIZE) + 1)
                .map { it.toQueueRow() }
        }

    /**
     * recordDuration은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param jobType 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param duration 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param completedAt 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun recordDuration(jobType: JobType, duration: Duration, completedAt: Instant) {
        require(!duration.isNegative && !duration.isZero) { "duration must be positive" }
        jdbc.exposedTransaction {
            JobDurationSampleTable.insert { statement ->
                statement[JobDurationSampleTable.jobType] = jobType.wireValue
                statement[JobDurationSampleTable.durationMillis] = duration.toMillis()
                statement[JobDurationSampleTable.completedAt] = completedAt
            }
        }
    }

    /**
     * durationSamples은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param jobType 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param since 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun durationSamples(jobType: JobType, since: Instant, limit: Int): List<Duration> {
        val boundedLimit = limit.coerceIn(1, MAX_DURATION_SAMPLES)
        return jdbc.exposedTransaction {
            JobDurationSampleTable
                .selectAll()
                .where {
                    (JobDurationSampleTable.jobType eq jobType.wireValue) and
                        (JobDurationSampleTable.completedAt greaterEq since)
                }
                .orderBy(
                    JobDurationSampleTable.completedAt to SortOrder.DESC,
                    JobDurationSampleTable.sampleId to SortOrder.DESC,
                )
                .limit(boundedLimit)
                .map { row -> Duration.ofMillis(row[JobDurationSampleTable.durationMillis]) }
        }
    }

    /**
     * hasActiveLease은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun hasActiveLease(tenantId: String): Boolean =
        JobTable
            .select(JobTable.jobId)
            .where {
                (JobTable.tenantId eq tenantId) and
                    (JobTable.state inList listOf(JobState.RUNNING.wireValue, JobState.CANCEL_REQUESTED.wireValue)) and
                    (JobTable.leaseExpiresAt greater CurrentTimestamp)
            }
            .limit(1)
            .any()

    /** 만료된 cancellation을 `FOR UPDATE`와 CAS `RETURNING`으로 한 번에 전이합니다. */
    /** 만료된 cancellation을 `FOR UPDATE`와 CAS `RETURNING`으로 한 번에 전이합니다. */
    private fun cancelExpiredLease(connection: Connection, tenantId: String): Boolean {
        val candidate =
            connection.prepareStatement(
                """
                SELECT job_id, version
                FROM jobs
                WHERE tenant_id = ? AND state = ? AND lease_expires_at <= CURRENT_TIMESTAMP
                ORDER BY enqueue_sequence
                FOR UPDATE
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.setString(2, JobState.CANCEL_REQUESTED.wireValue)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getObject(
                        "job_id",
                        UUID::class.java
                    ) to result.getLong("version") else null
                }
            } ?: return false
        val nextVersion =
            connection.prepareStatement(
                """
                UPDATE jobs
                SET state = ?, lease_token = NULL, lease_expires_at = NULL,
                    version = version + 1, queue_version = queue_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND version = ? AND state = ?
                RETURNING version
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, JobState.CANCELLED.wireValue)
                statement.setObject(2, candidate.first)
                statement.setLong(3, candidate.second)
                statement.setString(4, JobState.CANCEL_REQUESTED.wireValue)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                    result.getLong("version")
                }
            }
        appendTransition(
            connection,
            candidate.first,
            JobState.CANCEL_REQUESTED,
            JobState.CANCELLED,
            "expired_cancel_request",
            nextVersion,
        )
        return true
    }

    /** database clock, interval, `RETURNING`이 필요한 PostgreSQL lease 제어 경로입니다. */
    /** database clock, interval, `RETURNING`이 필요한 PostgreSQL lease 제어 경로입니다. */
    private fun activateLease(
        connection: Connection,
        tenantId: String,
        candidate: ClaimCandidate,
        leaseDuration: Duration,
        expectedState: JobState?,
    ): ClaimedJob {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "lease duration must be positive" }
        val token = Uuid.V7.nextId()
        val sql =
            buildString {
                append(
                    """
                    UPDATE jobs
                    SET state = ?, lease_token = ?,
                        lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                        attempt = attempt + 1, version = version + 1, queue_version = queue_version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE job_id = ? AND version = ?
                    """.trimIndent(),
                )
                if (expectedState != null) append(" AND state = ?")
                append(" RETURNING lease_expires_at, attempt, version")
            }
        val lease =
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, JobState.RUNNING.wireValue)
                statement.setObject(2, token)
                statement.setLong(3, leaseDuration.toMillis())
                statement.setObject(4, candidate.jobId)
                statement.setLong(5, candidate.version)
                if (expectedState != null) statement.setString(6, expectedState.wireValue)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                    JobLease(
                        jobId = candidate.jobId,
                        token = token,
                        attempt = result.getInt("attempt"),
                        expiresAt = result.getTimestamp("lease_expires_at").toInstant(),
                        revision = result.getLong("version"),
                    )
                }
            }
        JobAttemptTable.insert {
            it[jobId] = candidate.jobId
            it[attempt] = lease.attempt
            it[leaseToken] = token
        }
        appendTransition(connection, candidate.jobId, expectedState, JobState.RUNNING, "claimed", lease.revision)
        return ClaimedJob(
            lease = lease,
            tenantId = tenantId,
            jobType = candidate.jobType,
            failureMode = candidate.failureMode,
            workUnits = candidate.workUnits,
            enqueueSequence = candidate.enqueueSequence,
            completedChunk = checkpointChunk(candidate.jobId),
        )
    }

    /** 유효한 lease를 `FOR UPDATE`로 잠그는 PostgreSQL 제어 경로입니다. */
    /** 유효한 lease를 `FOR UPDATE`로 잠그는 PostgreSQL 제어 경로입니다. */
    private fun currentLeaseState(connection: Connection, lease: JobLease): LeaseState =
        connection.prepareStatement(
            "SELECT state, version, retry_budget FROM jobs " +
                    "WHERE job_id = ? AND lease_token = ? AND lease_expires_at > CURRENT_TIMESTAMP FOR UPDATE",
        ).use { statement ->
            statement.setObject(1, lease.jobId)
            statement.setObject(2, lease.token)
            statement.executeQuery().use { result ->
                if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                LeaseState(
                    state = JobState.entries.single { it.wireValue == result.getString("state") },
                    version = result.getLong("version"),
                    retryBudget = result.getInt("retry_budget"),
                )
            }
        }

    /** PostgreSQL `ON CONFLICT`와 database clock을 보존하는 checkpoint upsert입니다. */
    /** PostgreSQL `ON CONFLICT`와 database clock을 보존하는 checkpoint upsert입니다. */
    private fun upsertCheckpoint(connection: Connection, jobId: UUID, token: UUID, chunk: Long, progress: Int) {
        connection.prepareStatement(
            """
            INSERT INTO job_checkpoints(job_id, lease_token, completed_chunk, progress)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (job_id) DO UPDATE
            SET lease_token = EXCLUDED.lease_token, completed_chunk = EXCLUDED.completed_chunk,
                progress = EXCLUDED.progress, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, jobId)
            statement.setObject(2, token)
            statement.setLong(3, chunk)
            statement.setInt(4, progress)
            statement.executeUpdate()
        }
    }

    /**
     * checkpointChunk은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param jobId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun checkpointChunk(jobId: UUID): Long =
        JobCheckpointTable
            .select(JobCheckpointTable.completedChunk)
            .where { JobCheckpointTable.jobId eq jobId }
            .singleOrNull()
            ?.get(JobCheckpointTable.completedChunk)
            ?: 0L

    /** PostgreSQL `ON CONFLICT`와 `INSERT ... SELECT ... CURRENT_TIMESTAMP`를 보존하는 raw 경로입니다. */
    /** PostgreSQL `ON CONFLICT`와 `INSERT ... SELECT ... CURRENT_TIMESTAMP`를 보존하는 raw 경로입니다. */
    private fun appendTransition(
        connection: Connection,
        jobId: UUID,
        from: JobState?,
        to: JobState,
        reason: String,
        version: Long,
    ) {
        connection.prepareStatement(
            "INSERT INTO job_history(history_id, job_id, from_state, to_state, reason_code, resource_version, occurred_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
        ).use { statement ->
            statement.setObject(1, Uuid.V7.nextId())
            statement.setObject(2, jobId)
            statement.setString(3, from?.wireValue)
            statement.setString(4, to.wireValue)
            statement.setString(5, reason)
            statement.setLong(6, version)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO job_outbox(event_id, job_id, event_type, resource_version, queue_version, occurred_at)
            SELECT events.event_id, jobs.job_id, events.event_type, jobs.version, jobs.queue_version, CURRENT_TIMESTAMP
            FROM jobs
            CROSS JOIN (VALUES (?, 'job.updated'), (?, 'queue.updated')) AS events(event_id, event_type)
            WHERE jobs.job_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, Uuid.V7.nextId())
            statement.setObject(2, Uuid.V7.nextId())
            statement.setObject(3, jobId)
            statement.executeUpdate()
        }
    }

    /** 현재 Exposed transaction에서 job snapshot을 읽습니다. */
    /** 현재 Exposed transaction에서 job snapshot을 읽습니다. */
    internal fun loadInCurrentTransaction(jobId: UUID): StoredJob? {
        val job =
            JobTable
                .selectAll()
                .where { JobTable.jobId eq jobId }
                .singleOrNull()
                ?: return null
        val completedChunk =
            JobCheckpointTable
                .select(JobCheckpointTable.completedChunk)
                .where { JobCheckpointTable.jobId eq jobId }
                .singleOrNull()
                ?.get(JobCheckpointTable.completedChunk)
                ?: 0L
        return job.toStoredJob(completedChunk)
    }

/**
 * toStoredJob은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun ResultRow.toStoredJob(completedChunk: Long): StoredJob =
        StoredJob(
            jobId = this[JobTable.jobId],
            tenantId = this[JobTable.tenantId],
            submitterHash = this[JobTable.submitterHash].trim(),
            jobType = JobType.entries.single { it.wireValue == this[JobTable.jobType] },
            state = JobState.entries.single { it.wireValue == this[JobTable.state] },
            progress = this[JobTable.progress],
            completedChunk = completedChunk,
            enqueueSequence = this[JobTable.enqueueSequence],
            version = this[JobTable.version],
            updatedAt = this[JobTable.updatedAt],
        )

/**
 * toQueueRow은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun ResultRow.toQueueRow(): QueueRow =
        QueueRow(
            jobId = this[JobTable.jobId],
            jobType = JobType.entries.single { it.wireValue == this[JobTable.jobType] },
            state = JobState.entries.single { it.wireValue == this[JobTable.state] },
            enqueueSequence = this[JobTable.enqueueSequence],
            queueVersion = this[JobTable.queueVersion],
            updatedAt = this[JobTable.updatedAt],
        )

/**
 * inTransaction은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun <T> inTransaction(block: (Connection) -> T): T = jdbc.transaction(block)

    /** tenant/request 단위 직렬화를 위한 PostgreSQL transaction advisory lock입니다. */
    /** tenant/request 단위 직렬화를 위한 PostgreSQL transaction advisory lock입니다. */
    private fun advisoryLock(connection: Connection, value: String) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { result -> result.requireNext() }
        }
    }

    /**
     * normalizedSubmitterHash은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun normalizedSubmitterHash(value: String): String =
        if (value.matches(Regex("[0-9a-f]{64}"))) value else sha256(value)

    /**
     * sha256은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()).toHexString()

/**
 * requireNext은 운영 작업 콘솔 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun ResultSet.requireNext() {
        check(next()) { "expected one database row" }
    }

    /**
     * ExistingRequest은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private data class ExistingRequest(val fingerprint: String, val jobId: UUID)

    /**
     * LeaseState은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private data class LeaseState(val state: JobState, val version: Long, val retryBudget: Int)

    /**
     * CancelRow은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private data class CancelRow(
        /**
         * `tenantId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val tenantId: String,
        /**
         * `submitterHash`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val submitterHash: String,
        /**
         * `state`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val state: JobState,
        /**
         * `version`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val version: Long,
    )

    /**
     * ClaimCandidate은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private data class ClaimCandidate(
        /**
         * `jobId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val jobId: UUID,
        /**
         * `jobType`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val jobType: JobType,
        /**
         * `failureMode`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val failureMode: FailureMode,
        /**
         * `workUnits`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val workUnits: Int,
        /**
         * `enqueueSequence`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val enqueueSequence: Long,
        /**
         * `version`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val version: Long,
    ) {
        companion object {
            /**
             * from은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
             *
             * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
             * @param result 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
             */
            fun from(result: ResultSet): ClaimCandidate =
                ClaimCandidate(
                    jobId = result.getObject("job_id", UUID::class.java),
                    jobType = JobType.entries.single { it.wireValue == result.getString("job_type") },
                    failureMode = FailureMode.entries.single { it.wireValue == result.getString("failure_mode") },
                    workUnits = result.getInt("work_units"),
                    enqueueSequence = result.getLong("enqueue_sequence"),
                    version = result.getLong("version"),
                )
        }
    }
}
