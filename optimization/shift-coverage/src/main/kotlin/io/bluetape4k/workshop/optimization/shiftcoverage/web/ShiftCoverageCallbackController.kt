package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageProvider
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageCallbackCanonicalizer
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageSignatureContext
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageSignatureVerifier
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageInboxEvent
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageInboxService
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageLimits
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ProviderName
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.observability.ShiftCoverageObservations
import jakarta.servlet.http.HttpServletRequest
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** callback signature/target preflight를 inbox claim보다 먼저 수행하는 controller입니다. */
@Profile("demo")
@RestController
@RequestMapping("/api/shift-coverage/callbacks")
class ShiftCoverageCallbackController(
    private val inbox: ShiftCoverageInboxService,
    private val clock: Clock = Clock.systemUTC(),
    private val observations: ShiftCoverageObservations? = null,
) {
    private val verifier = ShiftCoverageSignatureVerifier(FIXTURE_SECRET)
    private val canonicalizer = ShiftCoverageCallbackCanonicalizer()

    @PostMapping("/{provider}")
    fun callback(
        request: HttpServletRequest,
        @PathVariable provider: String,
        @RequestBody body: ByteArray,
        @RequestHeader("X-Shift-Coverage-Signature", required = false) signature: String?,
        @RequestHeader("X-Shift-Coverage-Key-Version", required = false) keyVersion: String?,
        @RequestHeader("X-Shift-Coverage-Event-Id", required = false) eventId: String?,
        @RequestHeader("X-Request-Id", required = false) requestId: String?,
    ): ResponseEntity<Map<String, Any>> {
        if (body.size > ShiftCoverageLimits.MAX_BODY_BYTES) {
            throw ShiftCoverageHttpException(HttpStatus.valueOf(413), "RESPONSE_TOO_LARGE", false)
        }
        val canonicalBody = try {
            canonicalizer.parse(body)
            canonicalizer.canonicalBytes(body)
        } catch (failure: InvalidShiftCoverageInput) {
            throw ShiftCoverageHttpException(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", false)
        }
        if (request.remoteAddr !in LOOPBACK) throw ShiftCoverageHttpException(HttpStatus.FORBIDDEN, "LOOPBACK_REQUIRED", false)
        val parsedProvider = provider.toProviderOrNull() ?: throw ShiftCoverageHttpException(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", false)
        val id = eventId?.takeIf { it.isNotBlank() } ?: throw ShiftCoverageHttpException(HttpStatus.UNAUTHORIZED, "CALLBACK_SIGNATURE_INVALID", true)
        val signedRequestId = requestId?.takeIf { it.isNotBlank() }
            ?: throw ShiftCoverageHttpException(HttpStatus.UNAUTHORIZED, "CALLBACK_SIGNATURE_INVALID", true)
        val issuedAt = request.getHeader("X-Shift-Coverage-Issued-At")?.let { value ->
            try {
                Instant.parse(value)
            } catch (_: DateTimeParseException) {
                null
            }
        }
            ?: throw ShiftCoverageHttpException(HttpStatus.UNAUTHORIZED, "CALLBACK_SIGNATURE_INVALID", true)
        val datasetId = request.requiredSignedHeader("X-Shift-Coverage-Dataset-Id")
        val generationId = request.requiredSignedHeader("X-Shift-Coverage-Generation-Id")
        val aggregateId = request.requiredSignedHeader("X-Shift-Coverage-Plan-Id")
        val siteId = request.requiredSignedHeader("X-Shift-Coverage-Site-Id")
        val digest = request.requiredSignedHeader("X-Shift-Coverage-Digest")
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(canonicalBody).toHex()
        if (!MessageDigest.isEqual(digest.toByteArray(UTF_8), actualDigest.toByteArray(UTF_8))) {
            throw ShiftCoverageHttpException(HttpStatus.UNAUTHORIZED, "CALLBACK_SIGNATURE_INVALID", true)
        }
        val context = ShiftCoverageSignatureContext(
            method = "POST", path = request.requestURI, schemaVersion = "v1", provider = ProviderName(parsedProvider.name),
            requestId = signedRequestId, datasetId = datasetId,
            generationId = GenerationId(generationId), aggregateId = PlanId(aggregateId),
            siteId = SiteId(siteId), eventId = EventId(id), issuedAt = issuedAt,
        )
        val valid = verifier.verify(canonicalBody, context, signature, keyVersion, Instant.now(clock))
        if (!valid) throw ShiftCoverageHttpException(HttpStatus.UNAUTHORIZED, "CALLBACK_SIGNATURE_INVALID", true)
        val event = inbox.claim(ShiftCoverageInboxEvent(parsedProvider, EventId(id), digest, 0L))
        observations?.recordCallback(event.status.name)
        return ResponseEntity.accepted().body(mapOf("status" to event.status.name, "requestId" to signedRequestId))
    }

    private fun HttpServletRequest.requiredSignedHeader(name: String): String = getHeader(name)?.takeIf { it.isNotBlank() }
        ?: throw ShiftCoverageHttpException(HttpStatus.UNAUTHORIZED, "CALLBACK_SIGNATURE_INVALID", true)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun String.toProviderOrNull(): ShiftCoverageProvider? = ShiftCoverageProvider.entries.firstOrNull { it.name == this }

    companion object {
        private const val FIXTURE_SECRET = "shift-coverage-fixture-secret"
        private val LOOPBACK = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    }
}
