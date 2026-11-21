package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.javatimes.days
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionOutcome
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCanonicalizer
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCommand
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyCoordinator
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.Abandoned
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.Conflict
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.InFlightTimeout
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.OwnerCompleted
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.Replayed
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.WaiterOverflow
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnerAction
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PreparedJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.ReplayableJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.observability.DependencyState
import io.bluetape4k.workshop.operations.jobconsole.observability.JobConsoleReadiness
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JdbcJobSubmissionIdempotencyRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.queue.EtaEstimator
import io.bluetape4k.workshop.operations.jobconsole.queue.QueuePage
import io.bluetape4k.workshop.operations.jobconsole.queue.QueueProjectionService
import io.bluetape4k.workshop.operations.jobconsole.signal.CancelSignal
import io.bluetape4k.workshop.operations.jobconsole.signal.NoOpCancelSignal
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Duration
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
/**
 * CancelServiceOutcome은 운영 작업 콘솔에서 전달되는 불변 데이터 모델입니다.
 * @property jobId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 운영 작업 콘솔 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property signalDegraded 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


data class CancelServiceOutcome(
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
     * `signalDegraded`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val signalDegraded: Boolean,
)
/**
 * JobConsoleService은 운영 작업 콘솔에서 사용하는 업무 객체입니다.
 * @property repository 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property cancelSignal 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property etaEstimator 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property boundedWaitEnabled 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property expectedPolicyFingerprint 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property executor 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


class JobConsoleService(
    /**
     * `repository`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: JobRepository,
    /**
     * `cancelSignal`은 작업 운영 콘솔에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    private val cancelSignal: CancelSignal = NoOpCancelSignal,
    /**
     * `clock`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock = Clock.systemUTC(),
    /**
     * `etaEstimator`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val etaEstimator: EtaEstimator = EtaEstimator(),
    /**
     * `boundedWaitEnabled`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val boundedWaitEnabled: Boolean = true,
    /**
     * `expectedPolicyFingerprint`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val expectedPolicyFingerprint: String? = null,
    /**
     * `executor`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: ExecutorService = ForkJoinPool.commonPool(),
) : AutoCloseable {
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val submissionPolicy = JobSubmissionIdempotencyPolicy()
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val submissionCanonicalizer = JobSubmissionCanonicalizer()
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val submissionRepository =
        JdbcJobSubmissionIdempotencyRepository(repository.dataSource, repository, submissionPolicy, executor = executor)
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val submissionCoordinator =
        JobSubmissionIdempotencyCoordinator(submissionRepository, submissionPolicy)
    /**
     * 운영 작업 콘솔 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val jsonMapper = Jackson.defaultJsonMapper
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val acceptingSubmissions = AtomicBoolean(true)
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val activeSubmissions = AtomicInteger(0)

    /**
     * bounded-wait repository가 보유한 Exposed manager와 idempotency 리소스를 종료합니다.
     *
     * 이 메서드는 job을 취소하거나 DB 상태를 삭제하지 않습니다. adapter의 shutdown 순서에서
     * 신규 제출 차단과 submission quiescence 관찰이 끝난 뒤 호출해야 진행 중인 durable 결과를
     * 닫힌 리소스에 기록하지 않습니다.
     * 예:
     * ```kotlin
     * service.closeAdmission()
     * service.awaitSubmissionQuiescence(Duration.ofSeconds(5))
     * service.close()
     * ```
     */
    override fun close() {
        submissionRepository.close()
    }

    /**
     * `submit`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = submit(scope = scope, idempotencyKey = "demo-idempotency-1", request =
     * httpRequest)
     * ```
     * @param scope 요청 경계에서 검증된 `scope`입니다.
     * @param idempotencyKey 요청 경계에서 검증된 `idempotencyKey`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun submit(
        scope: DemoCallerScope,
        idempotencyKey: String,
        request: SubmitJobRequest,
    ): JobSubmissionOutcome {
        activeSubmissions.incrementAndGet()
        if (!acceptingSubmissions.get()) {
            activeSubmissions.decrementAndGet()
            throw JobRepositoryException(JobProblemCode.DEPENDENCY_UNAVAILABLE)
        }
        try {
            return if (boundedWaitEnabled) {
                submitBounded(scope, idempotencyKey, request)
            } else {
                submitLegacy(scope, idempotencyKey, request)
            }
        } finally {
            activeSubmissions.decrementAndGet()
        }
    }

    /**
     * shutdown drain 동안 새 제출을 받지 않도록 admission을 닫습니다.
     *
     * 이미 처리 중인 제출은 강제로 중단하지 않고 bounded wait 대상에 남깁니다.
     * 따라서 adapter는 이 메서드 뒤에 [awaitSubmissionQuiescence]를 호출해 active count가
     * 0이 되는지 확인한 후 repository를 닫아야 합니다.
     * 예:
     * ```kotlin
     * service.closeAdmission()
     * check(!service.isAcceptingSubmissions())
     * ```
     */
    fun closeAdmission() {
        acceptingSubmissions.set(false)
    }

    /**
     * `isAcceptingSubmissions`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = isAcceptingSubmissions()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun isAcceptingSubmissions(): Boolean = acceptingSubmissions.get()

    /**
     * `awaitSubmissionQuiescence`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = awaitSubmissionQuiescence(timeout = timeout)
     * ```
     * @param timeout 요청 경계에서 검증된 `timeout`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun awaitSubmissionQuiescence(timeout: Duration): Boolean {
        require(!timeout.isNegative) { "timeout must not be negative" }
        if (activeSubmissions.get() == 0) return true
        val deadline = System.nanoTime() + timeout.toNanos()
        while (activeSubmissions.get() > 0) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return false
            Thread.onSpinWait()
            if (remaining > 1.millis().toNanos()) {
                Thread.sleep(minOf(remaining.nanos().toMillis().coerceAtLeast(1L), 10L))
            }
        }
        return true
    }

    /**
     * `activeSubmissionCount`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = activeSubmissionCount()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun activeSubmissionCount(): Int = activeSubmissions.get()

    /**
     * submitBounded은 운영 작업 콘솔 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param request 운영 작업 콘솔 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun submitBounded(
        scope: DemoCallerScope,
        idempotencyKey: String,
        request: SubmitJobRequest,
    ): JobSubmissionOutcome {
        val command =
            JobSubmissionCommand(
                scope = scope,
                keyHash = submissionCanonicalizer.keyHash(scope, idempotencyKey),
                requestFingerprint = submissionCanonicalizer.fingerprint(request),
                request = request,
                policyFingerprint = submissionPolicy.fingerprint,
            )
        return toPublicOutcome(
            submissionCoordinator.execute(
                command,
                object: JobSubmissionOwnerAction {
                    /**
                     * `prepare`은 애플리케이션 공개 계약의 업무 진입점입니다.
                     *
                     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된
                     * 상태 전이만 수행합니다. 반환
                     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
                     * 예:
                     * ```kotlin
                     * val response = prepare(ownership = ownership)
                     * ```
                     * @param ownership 요청 경계에서 검증된 `ownership`입니다.
                     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
                     */
                    override fun prepare(ownership: JobSubmissionOwnership): PreparedJobSubmission {
                        val initial = JobSnapshot(
                            jobId = ownership.jobId,
                            jobType = request.jobType,
                            state = JobState.QUEUED,
                            progress = 0,
                            checkpoint = null,
                            queue = null,
                            version = 1,
                            updatedAt = clock.instant(),
                        )
                        return PreparedJobSubmission(
                            request = request,
                            responseBody = jsonMapper.writeValueAsString(initial).toUtf8Bytes(),
                        )
                    }

                    /**
                     * `commit`은 애플리케이션 공개 계약의 업무 진입점입니다.
                     *
                     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된
                     * 상태 전이만 수행합니다. 반환
                     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
                     * 예:
                     * ```kotlin
                     * val response = commit(connection = connection, ownership = ownership,
                     * prepared = prepared)
                     * ```
                     * @param connection 요청 경계에서 검증된 `connection`입니다.
                     * @param ownership 요청 경계에서 검증된 `ownership`입니다.
                     * @param prepared 요청 경계에서 검증된 `prepared`입니다.
                     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
                     */
                    override fun commit(
                        connection: java.sql.Connection,
                        ownership: JobSubmissionOwnership,
                        prepared: PreparedJobSubmission,
                    ): ReplayableJobSubmission =
                        submissionRepository.finalizeOwner(connection, ownership, prepared, clock.instant())
                },
            ),
        )
    }

    /**
     * submitLegacy은 운영 작업 콘솔 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param scope 업무 처리에 필요한 scope 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param request 운영 작업 콘솔 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun submitLegacy(
        scope: DemoCallerScope,
        idempotencyKey: String,
        request: SubmitJobRequest,
    ): JobSubmissionOutcome {
        val result = repository.submit(scope, idempotencyKey, request, clock.instant())
        val snapshot = snapshot(scope, result.jobId)
        val headers = mapOf("Idempotency-Replayed" to listOf(result.replayed.toString()))
        return if (result.replayed) {
            JobSubmissionOutcome.Replayed(snapshot, headers)
        } else {
            JobSubmissionOutcome.OwnerCompleted(snapshot, headers)
        }
    }

    /**
     * toPublicOutcome은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    private fun toPublicOutcome(
        outcome: io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome,
    ): JobSubmissionOutcome = when (outcome) {
        is OwnerCompleted ->
            JobSubmissionOutcome.OwnerCompleted(outcome.snapshot.toJobSnapshot(), outcome.snapshot.responseHeaders)
        is Replayed       ->
            JobSubmissionOutcome.Replayed(outcome.snapshot.toJobSnapshot(), outcome.snapshot.responseHeaders)

        Conflict          -> JobSubmissionOutcome.Conflict
        InFlightTimeout   -> JobSubmissionOutcome.InFlightTimeout
        WaiterOverflow    -> JobSubmissionOutcome.WaiterOverflow
        Abandoned         -> JobSubmissionOutcome.Abandoned
    }

    /**
     * toJobSnapshot은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun ReplayableJobSubmission.toJobSnapshot(): JobSnapshot =
        jsonMapper.readValue<JobSnapshot>(responseBody.toUtf8String())

    /**
     * `snapshot`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = snapshot(scope = scope, jobId = UUID.randomUUID())
     * ```
     * @param scope 요청 경계에서 검증된 `scope`입니다.
     * @param jobId 요청 경계에서 검증된 `jobId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun snapshot(scope: DemoCallerScope, jobId: UUID): JobSnapshot {
        val stored = repository.load(scope, jobId)
        val queue =
            if (stored.state.terminal) {
                null
            } else {
                val now = clock.instant()
                val base = repository.queueProjection(scope.tenantId, jobId, now) ?: return snapshot(scope, jobId)
                val estimate = etaEstimator.estimate(
                    repository.durationSamples(stored.jobType, now - 30.days(), 100),
                    base.jobsAhead,
                    now,
                )
                base.copy(
                    estimatedStartRange = estimate.startRange,
                    estimatedCompletionRange = estimate.completionRange,
                    confidence = estimate.confidence,
                    sampleSize = estimate.sampleSize,
                )
            }
        return JobSnapshot(
            jobId = stored.jobId,
            jobType = stored.jobType,
            state = stored.state,
            progress = stored.progress,
            checkpoint = stored.completedChunk.takeIf { it > 0 },
            queue = queue,
            version = stored.version,
            updatedAt = stored.updatedAt,
        )
    }

    /**
     * `myQueue`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = myQueue(scope = scope, cursor = null, pageSize = 25)
     * ```
     * @param scope 요청 경계에서 검증된 `scope`입니다.
     * @param cursor 요청 경계에서 검증된 `cursor`입니다.
     * @param pageSize 요청 경계에서 검증된 `pageSize`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun myQueue(scope: DemoCallerScope, cursor: String?, pageSize: Int): QueuePage =
        QueueProjectionService.page(
            repository.submitterQueuePageRows(scope, QueueProjectionService.cursorSequence(cursor), pageSize),
            null,
            pageSize,
        )

    /**
     * `tenantQueue`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = tenantQueue(tenantId = "tenant-a", cursor = null, pageSize = 25)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param cursor 요청 경계에서 검증된 `cursor`입니다.
     * @param pageSize 요청 경계에서 검증된 `pageSize`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun tenantQueue(tenantId: String, cursor: String?, pageSize: Int): QueuePage =
        QueueProjectionService.page(
            repository.queuePageRows(tenantId, QueueProjectionService.cursorSequence(cursor), pageSize),
            null,
            pageSize,
        )

    /**
     * `readiness`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = readiness()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun readiness(): JobConsoleReadiness {
        val postgresReady = repository.databaseReady()
        val redisAvailable = cancelSignal.isAvailable()
        val policyMatches = expectedPolicyFingerprint == null || expectedPolicyFingerprint == submissionPolicy.fingerprint

        val reason = when {
            !postgresReady -> "postgres"
            !policyMatches -> "policy"
            else -> null
        }

        return JobConsoleReadiness(
            ready = postgresReady && policyMatches,
            postgres = if (postgresReady) DependencyState.UP else DependencyState.DOWN,
            redis = if (redisAvailable) DependencyState.UP else DependencyState.DEGRADED,
            policyFingerprint = submissionPolicy.fingerprint,
            boundedWaitEnabled = boundedWaitEnabled,
            reason = reason,
        )
    }

    /**
     * `cancel`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = cancel(scope = scope, jobId = UUID.randomUUID())
     * ```
     * @param scope 요청 경계에서 검증된 `scope`입니다.
     * @param jobId 요청 경계에서 검증된 `jobId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun cancel(scope: DemoCallerScope, jobId: UUID): CancelServiceOutcome {
        val durable = repository.cancel(scope, jobId)
        val signalDegraded = durable.notificationRequired &&
                runCatching { cancelSignal.publish(jobId) }.getOrNull()?.delivered != true

        return CancelServiceOutcome(jobId, durable.state, signalDegraded)
    }
}
