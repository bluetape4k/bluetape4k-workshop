package io.bluetape4k.workshop.optimization.lastmile.adapter.http

import io.bluetape4k.workshop.optimization.lastmile.application.LastMileApprovalCommand
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileApprovalResult
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileApprovalService
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileCallbackService
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileEventCommand
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileEventService
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileReadModelService
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileReplanService
import io.bluetape4k.workshop.optimization.lastmile.domain.CarrierVersion
import io.bluetape4k.workshop.optimization.lastmile.domain.DriverId
import io.bluetape4k.workshop.optimization.lastmile.domain.JobId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEventType
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanId
import io.bluetape4k.workshop.optimization.lastmile.provider.CallbackDecision
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.security.MessageDigest

@RestController
@RequestMapping("/api/last-mile-routing")
internal class LastMileRoutingController(
    private val readModelService: LastMileReadModelService,
    private val replanService: LastMileReplanService,
    private val approvalService: LastMileApprovalService,
    private val callbackService: LastMileCallbackService,
    private val eventService: LastMileEventService,
) {
    @GetMapping("/plans/{planId}")
    fun plan(
        @PathVariable planId: String,
        @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?,
    ): ResponseEntity<Any> = etagged(
        readModelService.plan(LastMilePlanId(planId)) ?: throw NoSuchElementException("plan not found"),
        ifNoneMatch,
    )

    @PostMapping("/replans")
    fun replan(
        @Valid @RequestBody request: LastMileReplanHttpRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<LastMileReplanHttpResponse> {
        requireIdempotency(request.requestId, idempotencyKey)
        val receipt = replanService.replan(request.toCommand())
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            LastMileReplanHttpResponse(
                provider = receipt.submission.provider,
                requestId = receipt.submission.requestId,
                requestGeneration = receipt.submission.requestGeneration,
            ),
        )
    }

    @PostMapping("/plans/{planId}/approve")
    fun approve(
        @PathVariable planId: String,
        @Valid @RequestBody request: LastMileApprovalHttpRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<LastMileApprovalHttpResponse> {
        requireIdempotency("$planId:${request.planRevision}", idempotencyKey)
        val result = approvalService.approve(
            LastMileApprovalCommand(
                planId = LastMilePlanId(planId),
                planRevision = request.planRevision,
                expectedMatrixRevision = request.expectedMatrixRevision,
                expectedCarrierVersions = request.expectedCarrierVersions
                    .mapKeys { JobId(it.key) }
                    .mapValues { CarrierVersion(it.value) },
            ),
        )
        val status = if (result == LastMileApprovalResult.COMMITTED) HttpStatus.OK else HttpStatus.CONFLICT
        return ResponseEntity.status(status).body(LastMileApprovalHttpResponse(result.name))
    }

    @PostMapping("/providers/{provider}/callbacks")
    fun callback(
        @PathVariable provider: String,
        @Valid @RequestBody request: LastMileCallbackHttpRequest,
    ): ResponseEntity<LastMileCallbackHttpResponse> {
        require(provider == request.provider) { "provider path and envelope do not match" }
        val decision = callbackService.handle(request.toDomain())
        val status = if (decision == CallbackDecision.DIGEST_CONFLICT) HttpStatus.CONFLICT else HttpStatus.OK
        return ResponseEntity.status(status).body(LastMileCallbackHttpResponse(decision.name))
    }

    @PostMapping("/events")
    fun event(
        @Valid @RequestBody request: LastMileEventHttpRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<LastMileEventHttpResponse> {
        requireIdempotency(request.eventKey, idempotencyKey)
        val receipt = eventService.append(
            LastMileEventCommand(
                type = LastMileEventType.valueOf(request.type),
                aggregateId = request.aggregateId,
                eventKey = request.eventKey,
                payload = request.payload,
                occurredAt = request.occurredAt,
            ),
        )
        val status = if (receipt.appendResult.name == "DIGEST_CONFLICT") HttpStatus.CONFLICT else HttpStatus.OK
        return ResponseEntity.status(status).body(
            LastMileEventHttpResponse(
                eventId = receipt.event.eventId.value,
                result = receipt.appendResult.name,
                requestGeneration = receipt.requestGeneration,
                latestDigest = receipt.latestDigest,
            ),
        )
    }

    @PostMapping("/drivers/{driverId}/reconnect")
    fun reconnect(@PathVariable driverId: String): ResponseEntity<Any> =
        ResponseEntity.ok(readModelService.reconnect(DriverId(driverId)))

    private fun requireIdempotency(expected: String, actual: String?) {
        require(!actual.isNullOrBlank()) { "Idempotency-Key is required" }
        require(actual == expected || actual.matches(REQUEST_KEY_PATTERN)) { "Idempotency-Key is invalid" }
    }

    private fun <T : Any> etagged(body: T, ifNoneMatch: String?): ResponseEntity<Any> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(JSON.writeValueAsBytes(body))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val tag = "\"$digest\""
        return if (ifNoneMatch == tag || ifNoneMatch == "*") {
            ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(tag).build()
        } else {
            ResponseEntity.ok().eTag(tag).body(body)
        }
    }

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()
        val REQUEST_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,96}")
    }
}
