package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.optimization.fieldservice.application.CommandResult
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEventType
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitPin
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import jakarta.validation.Valid
import tools.jackson.databind.json.JsonMapper

@RestController
@RequestMapping("/api/field-service")
@Profile("demo")
internal class FieldServiceController(
    private val repository: FieldServiceRepository,
    private val service: FieldServiceHttpService,
    private val canonicalizer: FieldServiceCanonicalizer = FieldServiceCanonicalizer(),
) {
    @GetMapping("/visits")
    fun visits(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?,
    ): ResponseEntity<Any> = etagged(repository.findVisits(limit.coerceIn(1, FieldServiceLimits.MAX_PAGE_SIZE)), ifNoneMatch)

    @GetMapping("/workers")
    fun workers(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?,
    ): ResponseEntity<Any> = etagged(repository.findWorkers(limit.coerceIn(1, FieldServiceLimits.MAX_PAGE_SIZE)), ifNoneMatch)

    @GetMapping("/plans")
    fun plans(
        @RequestParam(defaultValue = "field-service") planId: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?,
    ): ResponseEntity<Any> = etagged(
        repository.listPlans(PlanId(planId), limit.coerceIn(1, FieldServiceLimits.MAX_PLAN_HISTORY))
            .map(::PlanResponse),
        ifNoneMatch,
    )

    @GetMapping("/plans/{revision}")
    fun plan(
        @PathVariable revision: Long,
        @RequestParam(defaultValue = "field-service") planId: String,
        @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?,
    ): ResponseEntity<Any> {
        val found = repository.loadPlan(PlanId(planId), revision) ?: throw NoSuchElementException("plan not found")
        return etagged(PlanResponse(found), ifNoneMatch)
    }

    @PostMapping("/visits")
    fun createVisit(
        @Valid @RequestBody request: CreateVisitRequest,
        @RequestHeader("X-Demo-Operator", required = false) operator: String?,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ): ResponseEntity<MutationResponse> = mutate(operator, key) {
        service.createVisit(request, requireNotNull(key))
    }

    @PostMapping("/visits/{id}/cancel")
    fun cancelVisit(@PathVariable id: String, @RequestHeader("X-Demo-Operator", required = false) operator: String?, @RequestHeader("Idempotency-Key", required = false) key: String?) = mutate(operator, key) {
        service.mutateVisit(VisitId(id), FieldServiceEventType.VISIT_CANCELLED, requireNotNull(key)) { it.copy(status = io.bluetape4k.workshop.optimization.fieldservice.domain.VisitStatus.CANCELLED, version = it.version + 1) }
    }

    @PostMapping("/visits/{id}/urgent")
    fun urgentVisit(@PathVariable id: String, @RequestHeader("X-Demo-Operator", required = false) operator: String?, @RequestHeader("Idempotency-Key", required = false) key: String?) = mutate(operator, key) {
        service.mutateVisit(VisitId(id), FieldServiceEventType.VISIT_URGENT, requireNotNull(key)) { it.copy(priority = io.bluetape4k.workshop.optimization.fieldservice.domain.VisitPriority.URGENT, version = it.version + 1) }
    }

    @PostMapping("/visits/{id}/pin")
    fun pinVisit(@PathVariable id: String, @Valid @RequestBody request: PinVisitRequest, @RequestHeader("X-Demo-Operator", required = false) operator: String?, @RequestHeader("Idempotency-Key", required = false) key: String?) = mutate(operator, key) {
        service.mutateVisit(
            visitId = VisitId(id),
            eventType = FieldServiceEventType.VISIT_PINNED,
            idempotencyKey = requireNotNull(key),
            payload = "${request.workerId}:${request.routeOrder}",
        ) { visit -> visit.copy(manualPin = VisitPin(WorkerId(request.workerId), request.routeOrder), version = visit.version + 1) }
    }

    @PostMapping("/visits/{id}/unpin")
    fun unpinVisit(@PathVariable id: String, @RequestHeader("X-Demo-Operator", required = false) operator: String?, @RequestHeader("Idempotency-Key", required = false) key: String?) = mutate(operator, key) {
        service.mutateVisit(VisitId(id), FieldServiceEventType.VISIT_UNPINNED, requireNotNull(key)) { visit ->
            if (visit.startedPin != null) throw IllegalStateException("started pin cannot be removed")
            visit.copy(manualPin = null, version = visit.version + 1)
        }
    }

    @PostMapping("/visits/{id}/no-show")
    fun noShow(@PathVariable id: String, @RequestHeader("X-Demo-Operator", required = false) operator: String?, @RequestHeader("Idempotency-Key", required = false) key: String?) = mutate(operator, key) {
        service.mutateVisit(VisitId(id), FieldServiceEventType.VISIT_NO_SHOW, requireNotNull(key)) { it.copy(status = io.bluetape4k.workshop.optimization.fieldservice.domain.VisitStatus.NO_SHOW, version = it.version + 1) }
    }

    @PostMapping("/workers/{id}/unavailable")
    fun unavailable(@PathVariable id: String, @RequestHeader("X-Demo-Operator", required = false) operator: String?, @RequestHeader("Idempotency-Key", required = false) key: String?) = mutate(operator, key) {
        service.mutateWorker(WorkerId(id), requireNotNull(key)) { it.copy(unavailable = true, version = it.version + 1) }
    }

    @PostMapping("/travel-times")
    fun travelTime(@Valid @RequestBody request: TravelTimeUpdateRequest, @RequestHeader("X-Demo-Operator", required = false) operator: String?, @RequestHeader("Idempotency-Key", required = false) key: String?) = mutate(operator, key) {
        service.recordTravelTime(request, requireNotNull(key))
    }

    @PostMapping("/plans/replan")
    fun replan(@Valid @RequestBody request: ReplanRequest, @RequestHeader("X-Demo-Operator", required = false) operator: String?, @RequestHeader("Idempotency-Key", required = false) key: String?): ResponseEntity<ReplanResponse> {
        requireMutationHeaders(operator, key)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ReplanResponse(service.replan(request, requireNotNull(key))))
    }

    @PostMapping("/plans/{revision}/approve")
    fun approve(@PathVariable revision: Long, @RequestParam(defaultValue = "field-service") planId: String, @RequestHeader("X-Demo-Operator", required = false) operator: String?, @RequestHeader("Idempotency-Key", required = false) key: String?): ResponseEntity<MutationResponse> = mutate(operator, key) {
        MutationResponse(service.approve(PlanId(planId), revision, requireNotNull(key)).name)
    }

    @PostMapping("/dispatch/workers/{workerId}/confirm")
    fun confirm(@PathVariable workerId: String, @RequestParam planId: String, @RequestParam revision: Long, @RequestHeader("X-Demo-Operator", required = false) operator: String?, @RequestHeader("Idempotency-Key", required = false) key: String?): ResponseEntity<MutationResponse> = mutate(operator, key) {
        MutationResponse(service.confirm(WorkerId(workerId), PlanId(planId), revision, requireNotNull(key)).name)
    }

    private fun mutate(operator: String?, key: String?, action: () -> Any): ResponseEntity<MutationResponse> {
        requireMutationHeaders(operator, key)
        val result = action()
        val value = when (result) {
            is MutationResponse -> result.result
            is CommandResult -> result.name
            else -> result.toString()
        }
        return ResponseEntity.ok(MutationResponse(value))
    }

    private fun requireMutationHeaders(operator: String?, key: String?) {
        if (operator != "true" || key.isNullOrBlank()) throw io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput("mutation requires X-Demo-Operator=true and Idempotency-Key")
        io.bluetape4k.workshop.optimization.fieldservice.domain.IdempotencyKey(key)
    }

    private fun <T : Any> etagged(body: T, ifNoneMatch: String?): ResponseEntity<Any> {
        val json = JSON.writeValueAsBytes(body)
        val tag = "\"${canonicalizer.digest(json).value}\""
        return if (ifNoneMatch == tag || ifNoneMatch == "*") {
            ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(tag).build()
        } else {
            ResponseEntity.ok().eTag(tag).body(body)
        }
    }

    private companion object {
        val JSON: JsonMapper = Jackson.defaultJsonMapper
    }
}
