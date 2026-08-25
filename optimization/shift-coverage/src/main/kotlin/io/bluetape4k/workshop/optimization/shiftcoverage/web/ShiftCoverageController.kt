package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageDemoService
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.IdempotencyKey
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** loopback과 closed demo operator guard를 적용한 command/query surface입니다. */
@RestController
@RequestMapping("/api/shift-coverage")
class ShiftCoverageController(private val service: ShiftCoverageDemoService) {
    @GetMapping("/plans")
    fun plans(request: HttpServletRequest): List<Any> {
        val principal = guard(request, requiredRole = null)
        return service.listPlans(principal.workerId).map { proposal ->
            if (principal.workerId == null) {
                ShiftCoveragePlanDto(
                    planId = proposal.planId.value,
                    revision = proposal.revision,
                    siteId = proposal.siteId?.value ?: "site-demo",
                    assignments = proposal.assignments.size,
                    gaps = proposal.unassigned.size,
                    coverageMinor = proposal.score.coverageMinor,
                    fairnessMinor = proposal.score.fairnessMinor,
                    reasons = proposal.unassigned.map { it.reason.name },
                )
            } else {
                ShiftCoverageWorkerPlanDto(
                    planId = proposal.planId.value,
                    revision = proposal.revision,
                    siteId = proposal.siteId?.value ?: "site-demo",
                    assignments = proposal.assignments.size,
                )
            }
        }
    }

    @GetMapping("/swaps")
    fun swaps(request: HttpServletRequest): List<Any> {
        val principal = guard(request, requiredRole = null)
        return service.listSwaps(principal.workerId)
    }

    @PostMapping("/replans")
    fun replan(
        request: HttpServletRequest,
        @RequestHeader("Idempotency-Key", required = true) idempotencyKey: String,
        @RequestHeader("X-Request-Id", required = false) requestId: String?,
    ): ResponseEntity<ShiftCoverageReplanResponse> {
        val principal = guard(request, requiredRole = "manager")
        val key = IdempotencyKey(idempotencyKey)
        val responseRequestId = requestId ?: Uuid.V7.nextId().toString()
        val result = service.replan(key, principal.operatorId, responseRequestId)
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("Retry-After", "1")
            .body(ShiftCoverageReplanResponse(true, result.proposal.revision, result.requestId))
    }

    @PostMapping("/plans/{revision}/approve")
    fun approve(
        request: HttpServletRequest,
        @PathVariable revision: Long,
        @RequestHeader("Idempotency-Key", required = true) idempotencyKey: String,
    ): ResponseEntity<Any> {
        val principal = guard(request, requiredRole = "manager")
        if (!service.approve(revision, IdempotencyKey(idempotencyKey), principal.operatorId)) {
            throw ShiftCoverageHttpException(HttpStatus.CONFLICT, "REVISION_CONFLICT", false)
        }
        return ResponseEntity.ok(mapOf("approved" to true, "revision" to revision))
    }

    @PostMapping("/swaps")
    fun requestSwap(
        request: HttpServletRequest,
        @RequestBody body: ShiftCoverageSwapRequestDto,
        @RequestHeader("Idempotency-Key", required = true) idempotencyKey: String,
    ): ResponseEntity<ShiftCoverageSwapResponse> {
        val principal = guard(request, requiredRole = "worker")
        val source = principal.workerId ?: throw ShiftCoverageHttpException(HttpStatus.FORBIDDEN, "DEMO_ROLE_FORBIDDEN", false)
        if (body.sourceWorkerId != source.value) {
            throw ShiftCoverageHttpException(HttpStatus.FORBIDDEN, "DEMO_SUBJECT_MISMATCH", false)
        }
        val swap = service.requestSwap(source, WorkerId(body.targetWorkerId), IdempotencyKey(idempotencyKey), principal.operatorId)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ShiftCoverageSwapResponse(swap.requestId.value, "REQUESTED"))
    }

    @PostMapping("/swaps/{requestId}/accept")
    fun acceptSwap(
        request: HttpServletRequest,
        @PathVariable requestId: String,
        @RequestHeader("Idempotency-Key", required = true) idempotencyKey: String,
    ): ResponseEntity<Any> {
        val principal = guard(request, requiredRole = "manager")
        if (!service.acceptSwap(requestId, IdempotencyKey(idempotencyKey), principal.operatorId)) {
            throw ShiftCoverageHttpException(HttpStatus.CONFLICT, "REVISION_CONFLICT", false)
        }
        return ResponseEntity.ok(mapOf("accepted" to true, "requestId" to requestId))
    }

    private fun guard(request: HttpServletRequest, requiredRole: String?): DemoPrincipal {
        val remote = request.remoteAddr
        if (remote !in LOOPBACK) throw ShiftCoverageHttpException(HttpStatus.FORBIDDEN, "LOOPBACK_REQUIRED", false)
        val origin = request.getHeader("Origin")
        if (origin != null && !isAllowedOrigin(origin)) {
            throw ShiftCoverageHttpException(HttpStatus.FORBIDDEN, "ORIGIN_FORBIDDEN", false)
        }
        val operator = request.getHeader("X-Demo-Operator") ?: throw ShiftCoverageHttpException(HttpStatus.FORBIDDEN, "DEMO_OPERATOR_REQUIRED", false)
        val role = request.getHeader("X-Demo-Role") ?: throw ShiftCoverageHttpException(HttpStatus.FORBIDDEN, "DEMO_ROLE_FORBIDDEN", false)
        if (requiredRole != null && role != requiredRole) throw ShiftCoverageHttpException(HttpStatus.FORBIDDEN, "DEMO_ROLE_FORBIDDEN", false)
        val principal = when (operator) {
            "manager-demo" -> DemoPrincipal(operator, "manager", null)
            "worker-a-demo" -> DemoPrincipal(operator, "worker", WorkerId("worker-a"))
            "worker-b-demo" -> DemoPrincipal(operator, "worker", WorkerId("worker-b"))
            else -> throw ShiftCoverageHttpException(HttpStatus.FORBIDDEN, "DEMO_OPERATOR_FORBIDDEN", false)
        }
        if (principal.role != role) throw ShiftCoverageHttpException(HttpStatus.FORBIDDEN, "DEMO_ROLE_FORBIDDEN", false)
        return principal
    }

    private fun isAllowedOrigin(value: String): Boolean = try {
        val origin = URI(value)
        origin.scheme == "http" && origin.userInfo == null && origin.path.isEmpty() &&
            origin.host in ALLOWED_ORIGIN_HOSTS && origin.query == null && origin.fragment == null
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {
        private val LOOPBACK = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
        private val ALLOWED_ORIGIN_HOSTS = setOf("127.0.0.1", "localhost")
    }
}

private data class DemoPrincipal(val operatorId: String, val role: String, val workerId: WorkerId?)

class ShiftCoverageHttpException(val status: HttpStatus, val code: String, val retryable: Boolean) : RuntimeException(code)
