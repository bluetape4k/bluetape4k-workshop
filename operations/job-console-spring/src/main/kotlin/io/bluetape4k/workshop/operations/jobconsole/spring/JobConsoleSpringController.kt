package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionHttpResponse
import io.bluetape4k.workshop.operations.jobconsole.application.BoundedJobEventFanout
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleUi
import io.bluetape4k.workshop.operations.jobconsole.application.JobOutboxPoller
import io.bluetape4k.workshop.operations.jobconsole.application.JobSubmissionHttpMapper
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.observability.JobConsoleReadiness
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.queue.QueuePage
import io.bluetape4k.workshop.operations.jobconsole.worker.JobWorkerEngine
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

@Profile("demo")
@RestController
@RequestMapping("/v1/jobs")
/**
 * Spring MVC translation of the shared job-console HTTP and SSE contract.
 *
 * The controller validates request framing at the adapter boundary and then
 * delegates state changes to [JobConsoleService]. A successful submission is
 * durable before the response is returned; an SSE event is only a refresh
 * hint, so callers should read the snapshot again.
 * ```bash
 * curl -i -X POST http://localhost:8080/v1/jobs \
 *   -H 'Content-Type: application/json' \
 *   -H 'X-Demo-Tenant: tenant-a' \
 *   -H 'X-Demo-Submitter: submitter-a' \
 *   -H 'Idempotency-Key: export-1' \
 *   -d '{"jobType":"document_export","workUnits":3,"failureMode":"none"}'
 * ```
 * @property service 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property fanout 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class JobConsoleSpringController(
    /**
     * `service`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: JobConsoleService,
    /**
     * `fanout`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val fanout: BoundedJobEventFanout,
) {

    companion object: KLogging()

    /**
     * `POST /v1/jobs` 요청을 파싱하고 PostgreSQL에 제출 결과가 확정된 뒤 HTTP 응답으로 변환합니다.
     *
     * tenant와 submitter scope는 body를 읽기 전에 확인해야 권한 없는 요청이 큰 body를 소비하지 않습니다.
     * `Idempotency-Key`가 같은 재전송은 새 job을 만들지 않고 저장된 owner/replay 결과를 그대로 반환하며,
     * 동일 키에 다른 payload를 보내면 `409 CONFLICT`로 거절합니다.
     * 예:
     * ```bash
     * curl -i -X POST http://localhost:8080/v1/jobs \
     *   -H 'Content-Type: application/json' \
     *   -H 'X-Demo-Tenant: tenant-a' \
     *   -H 'X-Demo-Submitter: submitter-a' \
     *   -H 'Idempotency-Key: export-1' \
     *   -d '{"jobType":"document_export","workUnits":3,"failureMode":"none"}'
     * ```
     */
    @PostMapping
    fun submit(request: HttpServletRequest): ResponseEntity<ByteArray> {
        // Scope is resolved before the idempotency key and body so authorization
        // failures cannot consume a potentially unbounded request stream.
        val scope = JobConsoleSpringSubmissionHttp.scope(request)
        val key = JobConsoleSpringSubmissionHttp.idempotencyKey(request)
        val submission = JobConsoleSpringSubmissionHttp.readSubmitRequest(request)
        return response(JobSubmissionHttpMapper.map(service.submit(scope, key, submission)))
    }

    /**
     * 한 caller scope가 볼 수 있는 job snapshot을 PostgreSQL source-of-truth에서 읽습니다.
     *
     * 반환 snapshot의 상태·revision·ETA가 운영 화면의 기준이며, SSE 이벤트는 변경 알림일 뿐 상태를 대체하지 않습니다.
     * 다른 tenant 또는 submitter의 job을 조회하면 scope 정책에 따라 `403` 또는 `404`를 반환합니다.
     * 예:
     * ```bash
     * curl http://localhost:8080/v1/jobs/018f4f12-7b36-7c00-9b2a-4d9b9e5e3a10 \
     *   -H 'X-Demo-Tenant: tenant-a' -H 'X-Demo-Submitter: submitter-a'
     * ```
     */
    @GetMapping("/{jobId}")
    fun snapshot(
        @PathVariable jobId: UUID,
        @RequestHeader("X-Demo-Tenant") tenant: String,
        @RequestHeader("X-Demo-Submitter") submitter: String,
    ): JobSnapshot =
        service.snapshot(DemoCallerScope(tenant, submitter), jobId)

    /**
     * 취소 command를 commit한 뒤 변경된 snapshot을 반환합니다.
     *
     * 취소 신호 전달이 지연되거나 worker가 이미 실행 중이어도 durable 상태 전이는 먼저 기록합니다.
     * 따라서 호출자는 응답의 `state`와 `signalDegraded`를 함께 보고 즉시 취소된 것과
     * worker가 추가 정리를 수행해야 하는 경우를 구분해야 합니다.
     * 예:
     * ```bash
     * curl -i -X POST http://localhost:8080/v1/jobs/018f4f12-7b36-7c00-9b2a-4d9b9e5e3a10/cancel \
     *   -H 'X-Demo-Tenant: tenant-a' -H 'X-Demo-Submitter: submitter-a'
     * ```
     */
    @PostMapping("/{jobId}/cancel")
    fun cancel(
        @PathVariable jobId: UUID,
        @RequestHeader("X-Demo-Tenant") tenant: String,
        @RequestHeader("X-Demo-Submitter") submitter: String,
    ): JobSnapshot {
        val scope = DemoCallerScope(tenant, submitter)
        service.cancel(scope, jobId)
        return service.snapshot(scope, jobId)
    }

    /**
     * job 변경 알림을 bounded SSE stream으로 전달합니다.
     *
     * 연결 수명과 fanout backlog에는 상한이 있으므로 연결이 끊기거나 heartbeat가 만료되면
     * 재연결한 caller가 반드시 [snapshot]을 먼저 읽어 현재 상태를 복구해야 합니다.
     * 이 stream의 event payload만으로 누락된 상태 전이를 추정하지 않습니다.
     * 예:
     * ```bash
     * curl -N http://localhost:8080/v1/jobs/018f4f12-7b36-7c00-9b2a-4d9b9e5e3a10/events \
     *   -H 'X-Demo-Tenant: tenant-a' -H 'X-Demo-Submitter: submitter-a'
     * ```
     */
    @GetMapping("/{jobId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @PathVariable jobId: UUID,
        @RequestHeader("X-Demo-Tenant") tenant: String,
        @RequestHeader("X-Demo-Submitter") submitter: String,
    ): SseEmitter {
        service.snapshot(DemoCallerScope(tenant, submitter), jobId)
        val emitter = SseEmitter(30_000)
        val subscription = fanout.subscribe("spring-${Uuid.V7.nextId()}") { event ->
            if (event.jobId == jobId) send(emitter, event)
        }
        emitter.onCompletion(subscription::close)
        emitter.onTimeout { subscription.close(); emitter.complete() }
        emitter.onError { subscription.close() }
        emitter.send(SseEmitter.event().name("heartbeat").data("{}"))
        return emitter
    }

    /**
     * send은 운영 작업 콘솔 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param emitter 업무 처리에 필요한 emitter 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param event 운영 작업 콘솔 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun send(emitter: SseEmitter, event: JobEvent) {
        emitter.send(
            SseEmitter.event()
                .id(event.eventId.toString())
                .name(event.eventType.wireValue)
                .data(event)
        )
    }

    /**
     * response은 운영 작업 콘솔의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * 예: `202` submission 응답은 jobId와 현재 snapshot만 반환하고 tenant·payload·stack trace를 노출하지 않습니다.
     */
    private fun response(result: JobSubmissionHttpResponse): ResponseEntity<ByteArray> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.parseMediaType(result.contentType)
        result.headers.forEach { (name, values) ->
            values.forEach {
                headers.add(name, it)
            }
        }
        return ResponseEntity.status(result.status).headers(headers).body(result.body)
    }
}

@Profile("demo")
@RestController
/**
 * Polls committed outbox rows without making an SSE client part of the transaction.
 * @property poller 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class JobConsoleOutboxSchedule(
    /**
     * publishEvents은 운영 작업 콘솔 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private val poller: JobOutboxPoller,
) {
    @Scheduled(fixedDelayString = "\${job-console.outbox-delay-ms:100}")
    /**
     * publishEvents은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    fun publishEvents() {
        poller.pollOnce()
    }
}

@Profile("demo")
@RestController
/**
 * Triggers one non-overlapping worker cycle on the Spring-owned executor.
 * @property workerEngine 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property jobWorkerExecutor 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class JobConsoleWorkerSchedule(
    /**
     * `workerEngine`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val workerEngine: JobWorkerEngine,
    /**
     * `jobWorkerExecutor`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val jobWorkerExecutor: ExecutorService,
) {
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val running = AtomicBoolean()

    @Scheduled(fixedDelayString = "\${job-console.worker-delay-ms:50}")
    /**
     * processNextJob은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    fun processNextJob() {
        if (!running.compareAndSet(false, true)) return

        jobWorkerExecutor.submit {
            try {
                workerEngine.runOnce()
            } finally {
                running.set(false)
            }
        }
    }
}

@Profile("demo")
@RestController
/**
 * Exposes bounded caller and operator queue projections.
 * @property service 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class JobConsoleSpringQueueController(
    /**
     * `service`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: JobConsoleService,
) {
    /**
     * `myQueue`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = myQueue(tenant = "tenant-a", submitter = "operator-a", cursor = null,
     * pageSize = 25)
     * ```
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param submitter 요청 경계에서 검증된 `submitter`입니다.
     * @param cursor 요청 경계에서 검증된 `cursor`입니다.
     * @param pageSize 요청 경계에서 검증된 `pageSize`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/v1/queues/me")
    fun myQueue(
        @RequestHeader("X-Demo-Tenant") tenant: String,
        @RequestHeader("X-Demo-Submitter") submitter: String,
        @RequestHeader("X-Queue-Cursor", required = false) cursor: String?,
        @RequestHeader("X-Queue-Page-Size", defaultValue = "25") pageSize: Int,
    ): QueuePage =
        service.myQueue(DemoCallerScope(tenant, submitter), cursor, pageSize)

    /**
     * `tenantQueue`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = tenantQueue(tenantId = "tenant-a", tenant = "tenant-a", operator = true,
     * cursor =
     * null, pageSize = 25)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param operator 요청 경계에서 검증된 `operator`입니다.
     * @param cursor 요청 경계에서 검증된 `cursor`입니다.
     * @param pageSize 요청 경계에서 검증된 `pageSize`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/v1/tenants/{tenantId}/queue")
    fun tenantQueue(
        @PathVariable tenantId: String,
        @RequestHeader("X-Demo-Tenant") tenant: String,
        @RequestHeader("X-Demo-Operator", defaultValue = "false") operator: Boolean,
        @RequestHeader("X-Queue-Cursor", required = false) cursor: String?,
        @RequestHeader("X-Queue-Page-Size", defaultValue = "25") pageSize: Int,
    ): QueuePage {
        if (!operator || tenant != tenantId) throw JobRepositoryException(JobProblemCode.SCOPE_DENIED)
        return service.tenantQueue(tenantId, cursor, pageSize)
    }
}

@Profile("demo")
@RestController
/**
 * Exposes the shared UI, liveness, and PostgreSQL/Redis readiness views.
 * @property service 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class JobConsoleSpringOperationsController(
    /**
     * `service`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: JobConsoleService,
) {
    /**
     * `index`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = index()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    fun index(): String = JobConsoleUi.indexHtml

    /**
     * `health`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = health()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/healthz")
    fun health(): Map<String, String> = mapOf("status" to "up")

    /**
     * `readiness`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = readiness()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/readyz")
    fun readiness(): ResponseEntity<JobConsoleReadiness> {
        val readiness = service.readiness()
        return ResponseEntity.status(if (readiness.ready) 200 else 503).body(readiness)
    }
}
