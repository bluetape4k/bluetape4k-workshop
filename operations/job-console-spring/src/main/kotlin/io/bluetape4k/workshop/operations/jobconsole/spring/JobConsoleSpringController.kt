package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.application.BoundedJobEventFanout
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import io.bluetape4k.workshop.operations.jobconsole.application.JobOutboxPoller
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.queue.QueuePage
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@Profile("demo")
@RestController
@RequestMapping("/v1/jobs")
class JobConsoleSpringController(
    private val service: JobConsoleService,
    private val fanout: BoundedJobEventFanout,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun submit(
        @RequestHeader("X-Demo-Tenant") tenant: String,
        @RequestHeader("X-Demo-Submitter") submitter: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: SubmitJobRequest,
    ): JobSnapshot = service.submit(DemoCallerScope(tenant, submitter), key, request)

    @GetMapping("/{jobId}")
    fun snapshot(
        @PathVariable jobId: UUID,
        @RequestHeader("X-Demo-Tenant") tenant: String,
        @RequestHeader("X-Demo-Submitter") submitter: String,
    ): JobSnapshot = service.snapshot(DemoCallerScope(tenant, submitter), jobId)

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

    @GetMapping("/{jobId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @PathVariable jobId: UUID,
        @RequestHeader("X-Demo-Tenant") tenant: String,
        @RequestHeader("X-Demo-Submitter") submitter: String,
    ): SseEmitter {
        service.snapshot(DemoCallerScope(tenant, submitter), jobId)
        val emitter = SseEmitter(30_000)
        val subscription =
            fanout.subscribe("spring-${UUID.randomUUID()}") { event ->
                if (event.jobId == jobId) send(emitter, event)
            }
        emitter.onCompletion(subscription::close)
        emitter.onTimeout { subscription.close(); emitter.complete() }
        emitter.onError { subscription.close() }
        return emitter
    }

    private fun send(emitter: SseEmitter, event: JobEvent) {
        emitter.send(SseEmitter.event().id(event.eventId.toString()).name(event.eventType.wireValue).data(event))
    }
}

@Profile("demo")
@RestController
class JobConsoleOutboxSchedule(
    private val poller: JobOutboxPoller,
) {
    @Scheduled(fixedDelayString = "\${job-console.outbox-delay-ms:100}")
    fun publishEvents() {
        poller.pollOnce()
    }
}

@Profile("demo")
@RestController
class JobConsoleSpringQueueController(
    private val service: JobConsoleService,
) {
    @GetMapping("/v1/queues/me")
    fun myQueue(
        @RequestHeader("X-Demo-Tenant") tenant: String,
        @RequestHeader("X-Demo-Submitter") submitter: String,
        @RequestHeader("X-Queue-Cursor", required = false) cursor: String?,
        @RequestHeader("X-Queue-Page-Size", defaultValue = "25") pageSize: Int,
    ): QueuePage = service.myQueue(DemoCallerScope(tenant, submitter), cursor, pageSize)

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
