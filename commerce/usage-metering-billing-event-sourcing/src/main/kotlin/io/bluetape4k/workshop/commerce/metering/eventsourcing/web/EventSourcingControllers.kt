package io.bluetape4k.workshop.commerce.metering.eventsourcing.web

import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.MeterCommandService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.MeterRegistration
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.ReconciliationQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.ReconciliationService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.config.EventSourcingMetrics
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventMetadata
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandAcquireResult
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandReceiptService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.BillingReadModelRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionFailureRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGeneration
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.LockSupport

data class RegisterMeterRequest(@field:NotBlank val code: String, @field:NotBlank val unit: String)
data class MeterResponse(val code: String, val unit: String)
data class ApiError(val code: String, val message: String)
data class BillingSummary(val tenantId: String, val generation: Int, val financialTotal: String)
data class ProjectionStatusResponse(
    val name: String,
    val generation: Int,
    val state: String,
    val checkpoint: Long,
    val highWatermark: Long,
    val lag: Long,
    val quarantined: Boolean,
    val failedPosition: Long?,
    val failureAttempts: Int?,
    val actorId: String? = null,
)

class ProjectionNotCaughtUpException : IllegalStateException("projection_not_caught_up")

@Component
class ProjectionConsistencyWaiter(
    private val generations: ProjectionGenerationRepository,
) {
    fun await(projectionName: String, targetPosition: Long, timeout: Duration = MAX_WAIT) {
        require(targetPosition >= 0) { "projection_position_invalid" }
        val deadline = System.nanoTime() + timeout.toNanos()
        while ((generations.active(projectionName)?.checkpoint ?: -1) < targetPosition) {
            if (System.nanoTime() >= deadline) throw ProjectionNotCaughtUpException()
            LockSupport.parkNanos(POLL_INTERVAL.toNanos())
        }
    }

    private companion object {
        val POLL_INTERVAL: Duration = Duration.ofMillis(5)
        val MAX_WAIT: Duration = Duration.ofMillis(100)
    }
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
class EventSourcingCommandController(
    private val meters: MeterCommandService,
    private val receipts: CommandReceiptService,
    private val mapper: ObjectMapper,
    private val metrics: EventSourcingMetrics,
    private val clock: Clock,
) {
    @PostMapping("/meters")
    @Transactional
    fun registerMeter(
        @PathVariable tenantId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: RegisterMeterRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        requireTenant(authentication, tenantId)
        val scope = CommandScope(tenantId, "meter-register", CommandFingerprint.key(key))
        val fingerprint = CommandFingerprint.request(
            "meter-register",
            mapOf("code" to request.code, "unit" to request.unit),
        )
        return when (val acquired = receipts.acquire(scope, fingerprint, clock.instant())) {
            is CommandAcquireResult.Owned -> registerOwned(tenantId, request, acquired, authentication.name)
            is CommandAcquireResult.Replay -> ResponseEntity.status(acquired.httpStatus)
                .header("Idempotency-Replayed", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(acquired.response)
            is CommandAcquireResult.InProgress -> ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", acquired.retryAfter.seconds.coerceAtLeast(1).toString())
                .body(ApiError("command_in_progress", "command_in_progress"))
            CommandAcquireResult.Conflict -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError("idempotency_conflict", "idempotency_conflict"))
        }
    }

    private fun registerOwned(
        tenantId: String,
        request: RegisterMeterRequest,
        owned: CommandAcquireResult.Owned,
        actorId: String,
    ): ResponseEntity<Any> {
        receipts.requireOwnership(owned, clock.instant())
        val startedAt = System.nanoTime()
        try {
            val commandId = owned.receiptId.toString()
            meters.register(MeterRegistration(
                tenantId,
                request.code,
                request.unit,
                DEFAULT_CURRENCY,
                clock.instant(),
                EventMetadata(commandId, commandId, commandId, actorId),
            ))
            metrics.recordAppend(APPEND_SUCCESS, Duration.ofNanos(System.nanoTime() - startedAt))
        } catch (failure: OptimisticConcurrencyException) {
            metrics.recordAppend(APPEND_CONFLICT, Duration.ofNanos(System.nanoTime() - startedAt))
            throw failure
        }
        val response = MeterResponse(request.code, request.unit)
        val receiptCompleted = receipts.succeed(
            owned,
            HttpStatus.CREATED.value(),
            mapper.writeValueAsString(response),
            clock.instant(),
        )
        check(receiptCompleted) {
            "command_receipt_owner_lost"
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    private companion object {
        const val DEFAULT_CURRENCY = "USD"
        const val APPEND_SUCCESS = "success"
        const val APPEND_CONFLICT = "conflict"
    }
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
class EventSourcingQueryController(
    private val generations: ProjectionGenerationRepository,
    private val readModels: BillingReadModelRepository,
    private val consistency: ProjectionConsistencyWaiter,
) {
    @GetMapping("/billing/summary")
    @Transactional(readOnly = true)
    fun summary(
        @PathVariable tenantId: String,
        @RequestHeader("X-Wait-For-Position", required = false) waitForPosition: Long?,
        authentication: Authentication,
    ): ResponseEntity<BillingSummary> {
        requireTenant(authentication, tenantId)
        waitForPosition?.let { consistency.await(BILLING_PROJECTION, it) }
        val active = checkNotNull(generations.active(BILLING_PROJECTION)) { "active_projection_missing" }
        val total = readModels.financialTotal(BILLING_PROJECTION, active.generation, tenantId)
        val lag = (active.highWatermark - active.checkpoint).coerceAtLeast(0)
        return ResponseEntity.ok()
            .header("Projection-Position", active.checkpoint.toString())
            .header("Projection-Lag", lag.toString())
            .body(BillingSummary(tenantId, active.generation, total.toPlainString()))
    }

    private companion object {
        const val BILLING_PROJECTION = "billing"
    }
}

@RestController
@RequestMapping("/api/admin/event-sourcing")
class EventSourcingAdminController(
    private val generations: ProjectionGenerationRepository,
    private val failures: ProjectionFailureRepository,
    private val rebuilds: ProjectionRebuildCommandService,
    private val reconciliation: ReconciliationService,
) {
    @GetMapping("/projections/{projectionName}")
    @Transactional(readOnly = true)
    fun projection(@PathVariable projectionName: String): ProjectionStatusResponse {
        val active = checkNotNull(generations.active(projectionName)) { "active_projection_missing" }
        return status(active)
    }

    @GetMapping("/projections/{projectionName}/generations/{generation}")
    @Transactional(readOnly = true)
    fun generation(
        @PathVariable projectionName: String,
        @PathVariable generation: Int,
    ): ProjectionStatusResponse = status(
        checkNotNull(generations.get(projectionName, generation)) { "projection_generation_missing" },
    )

    @PostMapping("/projections/{projectionName}/rebuilds")
    @Transactional
    fun rebuild(
        @PathVariable projectionName: String,
        @RequestHeader("Idempotency-Key") key: String,
        authentication: Authentication,
    ): ResponseEntity<Any> = rebuilds.begin(projectionName, key, authentication.name)

    @GetMapping("/reconciliation")
    @Transactional(readOnly = true)
    fun reconcile(
        @RequestParam tenantId: String,
        @RequestParam startsAt: Instant,
        @RequestParam endsAt: Instant,
    ): ResponseEntity<Any> {
        val finding = reconciliation.inspect(ReconciliationQuery(tenantId, BILLING_PROJECTION, startsAt, endsAt))
        return finding?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
    }

    private fun status(generation: ProjectionGeneration, actorId: String? = null): ProjectionStatusResponse {
        val failure = failures.latest(generation.projectionName, generation.generation)
        return ProjectionStatusResponse(
            generation.projectionName,
            generation.generation,
            generation.state.name,
            generation.checkpoint,
            generation.highWatermark,
            (generation.highWatermark - generation.checkpoint).coerceAtLeast(0),
            failure != null,
            failure?.globalPosition,
            failure?.attemptCount,
            actorId,
        )
    }

    private companion object {
        const val BILLING_PROJECTION = "billing"
    }
}

@RestControllerAdvice
class EventSourcingErrorAdvice {
    @ExceptionHandler(ProjectionNotCaughtUpException::class)
    fun projectionNotCaughtUp(): ResponseEntity<ApiError> = ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiError("projection_not_caught_up", "projection_not_caught_up"))

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiError("idempotency_key_required", "idempotency_key_required"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<ApiError> {
        val code = stableCode(failure.message, "invalid_request")
        return ResponseEntity.badRequest().body(ApiError(code, code))
    }

    @ExceptionHandler(IllegalStateException::class)
    fun conflict(failure: IllegalStateException): ResponseEntity<ApiError> {
        val code = stableCode(failure.message, "conflict")
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError(code, code))
    }

    private fun stableCode(message: String?, fallback: String): String = message
        ?.substringBefore(':')
        ?.takeIf { it.matches(STABLE_ERROR_CODE) }
        ?: fallback

    private companion object {
        val STABLE_ERROR_CODE = Regex("[a-z][a-z0-9_]*")
    }
}

private fun requireTenant(authentication: Authentication, tenantId: String) {
    require(authentication.name == tenantId) { "tenant_mismatch" }
}
