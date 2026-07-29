package io.bluetape4k.workshop.optimization.planning.web

import io.bluetape4k.workshop.optimization.planning.application.PlanningCallbackService
import io.bluetape4k.workshop.optimization.planning.application.PlanningCommandResult
import io.bluetape4k.workshop.optimization.planning.application.PlanningCommandService
import io.bluetape4k.workshop.optimization.planning.application.PlanningOutboxWorker
import io.bluetape4k.workshop.optimization.planning.application.PlanningQueryService
import io.bluetape4k.workshop.optimization.planning.application.PlanningRequestService
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@RestController
@RequestMapping("/api/planning")
internal class PlanningController(
    private val requestService: PlanningRequestService,
    private val worker: PlanningOutboxWorker,
    private val callbackService: PlanningCallbackService,
    private val commandService: PlanningCommandService,
    private val queryService: PlanningQueryService,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping("/requests")
    fun create(@Valid @RequestBody request: CreatePlanningRequestDto): ResponseEntity<PlanningCreatedResponse> {
        val created = requestService.create(request.toCommand())
        return ResponseEntity.accepted().body(PlanningCreatedResponse(created.id, created.status))
    }

    @PostMapping("/process")
    fun process(): ProcessedResponse {
        val tasks = worker.processDue()
        tasks.forEach { task -> task.get() }
        return ProcessedResponse(tasks.size)
    }

    @PostMapping("/callbacks/{provider}")
    fun callback(
        @PathVariable provider: String,
        @RequestHeader("X-Planning-Signature", required = false) signature: String?,
        request: HttpServletRequest,
    ): CallbackDecisionResponse {
        val normalizedProvider = PlanningProvider.valueOf(provider.replace('-', '_').uppercase())
        val rawBody = request.inputStream.use { input -> input.readNBytes(MAX_CALLBACK_BYTES + 1) }
        require(rawBody.size <= MAX_CALLBACK_BYTES) { "callback body is too large" }
        val callback = objectMapper.readValue(rawBody, PlanningCallbackDto::class.java)
        val decision = callbackService.handle(callback.toCommand(normalizedProvider), rawBody, signature)
        return CallbackDecisionResponse(decision.name)
    }

    @GetMapping("/requests/{requestId}")
    fun find(@PathVariable requestId: UUID) = queryService.find(requestId)

    @PostMapping("/requests/{requestId}/commands")
    fun command(@PathVariable requestId: UUID): ResponseEntity<PlanningCommandResult> =
        when (val result = commandService.createCandidate(requestId)) {
            is PlanningCommandResult.Ready -> ResponseEntity.ok(result)
            is PlanningCommandResult.Conflict -> ResponseEntity.status(409).body(result)
        }

    companion object {
        private const val MAX_CALLBACK_BYTES = 256 * 1024
    }
}
