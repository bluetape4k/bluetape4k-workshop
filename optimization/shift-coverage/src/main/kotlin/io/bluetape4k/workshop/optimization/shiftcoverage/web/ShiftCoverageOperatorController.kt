package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageInboxService
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageOutboxStore
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageProvider
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EffectKey
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** retry exhausted inbox와 definitive NOT_FOUND outbox만 manager가 명시적으로 재처리합니다. */
@Profile("demo")
@RestController
@RequestMapping("/api/shift-coverage")
class ShiftCoverageOperatorController(
    private val inbox: ShiftCoverageInboxService,
    private val outbox: ShiftCoverageOutboxStore,
) {
    @PostMapping("/outbox/{effectKey}/redrive")
    fun redrive(
        request: HttpServletRequest,
        @PathVariable effectKey: String,
        @RequestHeader("X-Demo-Operator", required = true) operator: String,
        @RequestHeader("X-Demo-Role", required = true) role: String,
        @RequestHeader("X-Operator-Reason", required = true) reason: String,
    ): ResponseEntity<Map<String, Any?>> {
        requireManager(request, operator, role)
        val record = outbox.redrive(EffectKey(effectKey), operator, reason)
        return ResponseEntity.accepted().body(mapOf("status" to record?.status?.name, "effectKey" to effectKey))
    }

    @PostMapping("/inbox/{provider}/{eventId}/requeue")
    fun requeue(
        request: HttpServletRequest,
        @PathVariable provider: String,
        @PathVariable eventId: String,
        @RequestHeader("X-Demo-Operator", required = true) operator: String,
        @RequestHeader("X-Demo-Role", required = true) role: String,
        @RequestHeader("X-Operator-Reason", required = true) reason: String,
    ): ResponseEntity<Map<String, Any?>> {
        requireManager(request, operator, role)
        val parsed = ShiftCoverageProvider.entries.firstOrNull { it.name == provider }
            ?: throw ShiftCoverageHttpException(org.springframework.http.HttpStatus.BAD_REQUEST, "REQUEST_INVALID", false)
        val record = inbox.requeue(parsed, EventId(eventId), reason)
        return ResponseEntity.accepted().body(mapOf("status" to record.status.name, "requestId" to record.requestId))
    }

    private fun requireManager(request: HttpServletRequest, operator: String, role: String) {
        if (request.remoteAddr !in LOOPBACK) {
            throw ShiftCoverageHttpException(org.springframework.http.HttpStatus.FORBIDDEN, "LOOPBACK_REQUIRED", false)
        }
        val origin = request.getHeader("Origin")
        if (origin != null && !isAllowedOrigin(origin)) {
            throw ShiftCoverageHttpException(org.springframework.http.HttpStatus.FORBIDDEN, "ORIGIN_FORBIDDEN", false)
        }
        if (operator != "manager-demo" || role != "manager") {
            throw ShiftCoverageHttpException(org.springframework.http.HttpStatus.FORBIDDEN, "DEMO_ROLE_FORBIDDEN", false)
        }
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
