package io.bluetape4k.workshop.operations.jobconsole.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.operations.jobconsole.api.JobConsoleJson
import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import io.bluetape4k.workshop.operations.jobconsole.application.BoundedJobEventFanout
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleUi
import io.bluetape4k.workshop.operations.jobconsole.application.JobOutboxPoller
import io.bluetape4k.workshop.operations.jobconsole.application.JobSubmissionHttpMapper
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigration
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigrationRunner
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.signal.LettuceCancelSignal
import io.bluetape4k.workshop.operations.jobconsole.signal.NoOpCancelSignal
import io.bluetape4k.workshop.operations.jobconsole.worker.DeterministicJobWorkload
import io.bluetape4k.workshop.operations.jobconsole.worker.JobWorkerEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.util.*
import java.util.concurrent.ExecutorService
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `mapper`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private val mapper = JobConsoleJson.defaultMapper

/**
 * Installs the Ktor job-console contract and its application-owned workers.
 *
 * Set [demoEnabled] only for the workshop routes. Blocking JDBC/service calls
 * are isolated on `Dispatchers.IO`; cancellation is rethrown so Ktor can
 * cancel the request instead of turning structured cancellation into a
 * generic dependency error. PostgreSQL remains authoritative when [redisUri]
 * is absent or unavailable.
 * ```kotlin
 * embeddedServer(Netty, port = 8080) {
 *     jobConsoleModule(dataSource, demoEnabled = true)
 * }.start(wait = true)
 * ```
 */
fun Application.jobConsoleModule(
    dataSource: DataSource,
    demoEnabled: Boolean = false,
    redisUri: String? = null,
    boundedWaitEnabled: Boolean = boundedWaitEnabledFromEnvironment(),
) {
    jobConsoleModule(
        dataSource = dataSource,
        demoEnabled = demoEnabled,
        redisUri = redisUri,
        workerEnabled = demoEnabled,
        boundedWaitEnabled = boundedWaitEnabled,
    )
}

/**
 * jobConsoleModule은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param dataSource 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param demoEnabled 업무 처리에 필요한 demoEnabled 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param redisUri 운영 작업 콘솔 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
 * @param workerEnabled 업무 처리에 필요한 workerEnabled 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param boundedWaitEnabled 업무 처리에 필요한 boundedWaitEnabled 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param outboxStartGate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param workerStartGate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param runtimeObserver 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun Application.jobConsoleModule(
    dataSource: DataSource,
    demoEnabled: Boolean,
    redisUri: String?,
    workerEnabled: Boolean,
    boundedWaitEnabled: Boolean = boundedWaitEnabledFromEnvironment(),
    outboxStartGate: suspend () -> Unit = {},
    workerStartGate: suspend () -> Unit = {},
    runtimeObserver: (JobConsoleKtorRuntime) -> Unit = {},
) {
    JobMigrationRunner(
        dataSource,
        listOf(
            JobMigration.classpath("001", "db/job-console/V001__job_console.sql"),
            JobMigration.classpath("002", "db/job-console/V002__bounded_wait_http_idempotency.sql"),
        ),
        advisoryLockKey = 520_001L,
    ).migrate()
    /**
     * `repository`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val repository = JobRepository(dataSource)
    /**
     * `redisSignal`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val redisSignal = redisUri?.takeIf(String::isNotBlank)?.let { runCatching { LettuceCancelSignal(it) }.getOrNull() }
    /**
     * `executor`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val executor = VirtualThreads.executorService()
    /**
     * `service`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val service = JobConsoleService(
        repository = repository,
        cancelSignal = redisSignal ?: NoOpCancelSignal,
        boundedWaitEnabled = boundedWaitEnabled,
        expectedPolicyFingerprint = System.getenv("JOB_CONSOLE_BOUNDED_WAIT_POLICY_FINGERPRINT")
            ?.takeIf(String::isNotBlank),
        executor = executor,
    )
    /**
     * `fanout`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val fanout = BoundedJobEventFanout(Duration.ofSeconds(2), executor)
    /**
     * `outboxRepository`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val outboxRepository = JobOutboxRepository(dataSource)
    /**
     * `poller`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val poller = JobOutboxPoller(outboxRepository, fanout)
    /**
     * `workerEngine`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val workerEngine = JobWorkerEngine(repository, DeterministicJobWorkload())

    install(CallLogging)
    install(SSE)
    install(StatusPages) {
        exception<KtorJobSubmissionScopeDeniedException> { call, _ ->
            call.respondSubmissionProblem(
                JobSubmissionHttpMapper.problem(JobProblemCode.SCOPE_DENIED, 403, "Forbidden"),
            )
        }
        exception<KtorJobSubmissionRequestTooLargeException> { call, _ ->
            call.respondSubmissionProblem(
                JobSubmissionHttpMapper.problem(JobProblemCode.IDEMPOTENCY_REQUEST_TOO_LARGE, 413, "Payload Too Large"),
            )
        }
        exception<KtorJobSubmissionInvalidRequestException> { call, _ ->
            call.respondSubmissionProblem(
                JobSubmissionHttpMapper.problem(JobProblemCode.INVALID_IDEMPOTENCY_REQUEST, 400, "Bad Request"),
            )
        }
        exception<JobRepositoryException> { call, failure ->
            /**
             * `status`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
             *
             * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
             */
            val status =
                when (failure.code) {
                    JobProblemCode.JOB_NOT_FOUND -> HttpStatusCode.NotFound
                    JobProblemCode.SCOPE_DENIED -> HttpStatusCode.Forbidden
                    JobProblemCode.IDEMPOTENCY_KEY_REUSED -> HttpStatusCode.Conflict
                    JobProblemCode.IDEMPOTENCY_IN_FLIGHT -> HttpStatusCode.Conflict
                    JobProblemCode.IDEMPOTENCY_WAITERS_EXCEEDED -> HttpStatusCode.TooManyRequests
                    JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED -> HttpStatusCode.InternalServerError
                    JobProblemCode.DEPENDENCY_UNAVAILABLE,
                    JobProblemCode.LEASE_LOST,
                        -> HttpStatusCode.ServiceUnavailable
                    else -> HttpStatusCode.Conflict
                }
            call.respondSubmissionProblem(
                JobSubmissionHttpMapper.problem(
                    failure.code,
                    status.value,
                    status.description
                )
            )
        }
        exception<IllegalArgumentException> { call, _ ->
            call.respondSubmissionProblem(
                JobSubmissionHttpMapper.problem(
                    JobProblemCode.VALIDATION_FAILED,
                    400,
                    "Bad Request"
                )
            )
        }
        exception<CancellationException> { _, failure -> throw failure }
        exception<Throwable> { call, _ ->
            call.respondSubmissionProblem(
                JobSubmissionHttpMapper.problem(
                    JobProblemCode.DEPENDENCY_UNAVAILABLE,
                    503,
                    "Service Unavailable"
                )
            )
        }
    }

    if (demoEnabled) installJobConsoleRoutes(service, fanout)

    /**
     * `outboxJob`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val outboxJob = launch(Dispatchers.IO) {
        outboxStartGate()
        while (isActive) {
            try {
                poller.pollOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                environment.log.warn("Job console background cycle failed", failure)
            }
            delay(100.milliseconds)
        }
    }
    /**
     * `workerJob`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val workerJob =
        if (workerEnabled) {
            launch(Dispatchers.IO) {
                workerStartGate()
                while (isActive) {
                    try {
                        workerEngine.runOnce()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        environment.log.warn("Job console worker cycle failed", failure)
                    }
                    delay(50.milliseconds)
                }
            }
        } else {
            null
        }
    runtimeObserver(
        JobConsoleKtorRuntime(
            service = service,
            repository = repository,
            outboxRepository = outboxRepository,
            workerEngine = workerEngine,
            outboxJob = outboxJob,
            workerJob = workerJob,
            redisSignal = redisSignal,
            executor = executor,
        ),
    )
    monitor.subscribe(ApplicationStopped) {
        service.closeAdmission()
        runBlocking(Dispatchers.IO) {
            service.awaitSubmissionQuiescence(Duration.ofSeconds(5))
            workerJob?.cancelAndJoin()
            outboxJob.cancelAndJoin()
        }
        redisSignal?.close()
        service.close()
        outboxRepository.close()
        repository.close()
        executor.close()
        (dataSource as? AutoCloseable)?.close()
    }
}
/**
 * JobConsoleKtorRuntime은 운영 작업 콘솔에서 사용하는 업무 객체입니다.
 * @property service 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property repository 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property outboxRepository 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property workerEngine 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property outboxJob 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property workerJob 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property redisSignal 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property executor 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class JobConsoleKtorRuntime(
    /**
     * `service`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val service: JobConsoleService,
    /**
     * `repository`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val repository: JobRepository,
    /**
     * `outboxRepository`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val outboxRepository: JobOutboxRepository,
    /**
     * `workerEngine`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val workerEngine: JobWorkerEngine,
    /**
     * `outboxJob`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val outboxJob: Job,
    /**
     * `workerJob`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val workerJob: Job?,
    /**
     * `redisSignal`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val redisSignal: LettuceCancelSignal?,
    /**
     * `executor`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val executor: ExecutorService,
) {
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val backgroundJobsStopped: Boolean
        get() = !outboxJob.isActive && workerJob?.isActive != true
}

/**
 * boundedWaitEnabledFromEnvironment은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun boundedWaitEnabledFromEnvironment(): Boolean =
    System.getenv("JOB_CONSOLE_BOUNDED_WAIT_ENABLED")?.toBooleanStrictOrNull() ?: false

/**
 * installJobConsoleRoutes은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param service 업무 처리에 필요한 service 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param fanout 업무 처리에 필요한 fanout 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun Application.installJobConsoleRoutes(service: JobConsoleService, fanout: BoundedJobEventFanout) {
    routing {
        get("/") {
            call.respondText(JobConsoleUi.indexHtml, ContentType.Text.Html, HttpStatusCode.OK)
        }
        get("/healthz") {
            call.respondJson(HttpStatusCode.OK, mapOf("status" to "up"))
        }
        get("/readyz") {
            val readiness = withContext(Dispatchers.IO) { service.readiness() }
            call.respondJson(if (readiness.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, readiness)
        }
        route("/v1/jobs") {
            post {
                val scope = JobConsoleKtorSubmissionHttp.scope(call)
                val key = JobConsoleKtorSubmissionHttp.idempotencyKey(call)
                val request = JobConsoleKtorSubmissionHttp.readSubmitRequest(call)
                val outcome = runInterruptible(Dispatchers.IO) { service.submit(scope, key, request) }
                call.respondSubmission(JobSubmissionHttpMapper.map(outcome))
            }
            get("/{jobId}") {
                val jobId = UUID.fromString(requireNotNull(call.parameters["jobId"]))
                call.respondJson(
                    HttpStatusCode.OK,
                    withContext(Dispatchers.IO) { service.snapshot(call.demoScope(), jobId) })
            }
            post("/{jobId}/cancel") {
                val jobId = UUID.fromString(requireNotNull(call.parameters["jobId"]))
                val scope = call.demoScope()
                withContext(Dispatchers.IO) { service.cancel(scope, jobId) }
                call.respondJson(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.snapshot(scope, jobId) })
            }
        }
        get("/v1/queues/me") {
            val cursor = call.request.headers["X-Queue-Cursor"]
            val pageSize = call.request.headers["X-Queue-Page-Size"]?.toIntOrNull() ?: 25
            call.respondJson(
                HttpStatusCode.OK,
                withContext(Dispatchers.IO) { service.myQueue(call.demoScope(), cursor, pageSize) }
            )
        }
        get("/v1/tenants/{tenantId}/queue") {
            val tenantId = requireNotNull(call.parameters["tenantId"])
            val scope = call.demoScope()
            if (call.request.headers["X-Demo-Operator"] != "true" || scope.tenantId != tenantId) {
                throw JobRepositoryException(JobProblemCode.SCOPE_DENIED)
            }
            val cursor = call.request.headers["X-Queue-Cursor"]
            val pageSize = call.request.headers["X-Queue-Page-Size"]?.toIntOrNull() ?: 25
            call.respondJson(
                HttpStatusCode.OK,
                withContext(Dispatchers.IO) { service.tenantQueue(tenantId, cursor, pageSize) }
            )
        }
        sse("/v1/jobs/{jobId}/events") {
            val jobId = UUID.fromString(call.parameters["jobId"].requireNotNull("jobId"))
            withContext(Dispatchers.IO) { service.snapshot(call.demoScope(), jobId) }
            val channel = Channel<JobEvent>(capacity = 16)
            val subscription = fanout.subscribe("ktor-${Uuid.V7.nextId()}") {
                if (it.jobId == jobId) channel.trySend(it)
            }
            try {
                send(ServerSentEvent(data = "{}", event = "heartbeat"))
                while (true) {
                    val event = withTimeoutOrNull(10.seconds) { channel.receive() }
                    if (event == null) {
                        send(ServerSentEvent(data = "{}", event = "heartbeat"))
                    } else {
                        send(
                            ServerSentEvent(
                                data = mapper.writeValueAsString(event),
                                event = event.eventType.wireValue,
                                id = event.eventId.toString(),
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                subscription.close()
                channel.close()
            }
        }
    }
}

/**
 * demoScope은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun io.ktor.server.application.ApplicationCall.demoScope(): DemoCallerScope =
    DemoCallerScope(
        tenantId = requireNotNull(request.headers["X-Demo-Tenant"]) { "X-Demo-Tenant is required" },
        submitterHash = requireNotNull(request.headers["X-Demo-Submitter"]) { "X-Demo-Submitter is required" },
    )

/**
 * respondJson은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondJson(status: HttpStatusCode, value: Any) {
    respondText(mapper.writeValueAsString(value), ContentType.Application.Json, status)
}

/** Starts the Ktor Netty adapter using the documented environment variables. */
/** Starts the Ktor Netty adapter using the documented environment variables. */
fun main() {
    val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = environment("POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/postgres")
            username = environment("POSTGRES_USERNAME", "postgres")
            password = environment("POSTGRES_PASSWORD", "postgres")
            schema = environment("POSTGRES_SCHEMA", "public")
        },
    )
    embeddedServer(Netty, port = environment("PORT", "8080").toInt()) {
        jobConsoleModule(
            dataSource,
            demoEnabled = environment("JOB_CONSOLE_DEMO", "false").toBoolean(),
            redisUri = environment("REDIS_URI", "").ifBlank { null },
        )
    }.start(wait = true)
}

/**
 * environment은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param default 업무 처리에 필요한 default 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun environment(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: System.getProperty(name.lowercase().replace('_', '.')) ?: default
