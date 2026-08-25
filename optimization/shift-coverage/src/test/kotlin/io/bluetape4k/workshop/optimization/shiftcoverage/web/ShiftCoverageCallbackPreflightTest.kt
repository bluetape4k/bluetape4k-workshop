package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageProvider
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageSignatureContext
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageSignatureVerifier
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageInboxService
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageInboxStatus
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ProviderName
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

class ShiftCoverageCallbackPreflightTest {
    private val body = "{\"event\":\"availability.changed\"}".toByteArray()
    private val issuedAt = Instant.parse("2026-08-24T09:00:00Z")

    @Test
    fun `invalid signature is rejected before inbox claim`() {
        val inbox = ShiftCoverageInboxService()
        val controller = ShiftCoverageCallbackController(inbox, Clock.fixed(issuedAt, ZoneOffset.UTC))
        val request = request("event-invalid").apply { addHeader("X-Shift-Coverage-Issued-At", issuedAt.toString()) }

        val failure = assertFailsWith<ShiftCoverageHttpException> {
            controller.callback(request, "FAKE", body, null, null, "event-invalid", "request-invalid")
        }

        failure.status shouldBeEqualTo HttpStatus.UNAUTHORIZED
        inbox.find(ShiftCoverageProvider.FAKE, EventId("event-invalid")) shouldBeEqualTo null
    }

    @Test
    fun `valid signature claims the event after preflight`() {
        val inbox = ShiftCoverageInboxService()
        val controller = ShiftCoverageCallbackController(inbox, Clock.fixed(issuedAt, ZoneOffset.UTC))
        val requestId = "request-valid"
        val eventId = EventId("event-valid")
        val context = ShiftCoverageSignatureContext(
            method = "POST", path = "/api/shift-coverage/callbacks/FAKE", schemaVersion = "v1",
            provider = ProviderName("FAKE"), requestId = requestId, datasetId = "dataset-demo",
            generationId = GenerationId("generation-demo"), aggregateId = PlanId("plan-demo"),
            siteId = SiteId("site-demo"), eventId = eventId, issuedAt = issuedAt,
        )
        val signature = ShiftCoverageSignatureVerifier("shift-coverage-fixture-secret").sign(body, context, "fixture-v1")
        val request = request("event-valid").apply {
            addHeader("X-Shift-Coverage-Issued-At", issuedAt.toString())
            addHeader("X-Shift-Coverage-Dataset-Id", "dataset-demo")
            addHeader("X-Shift-Coverage-Generation-Id", "generation-demo")
            addHeader("X-Shift-Coverage-Plan-Id", "plan-demo")
            addHeader("X-Shift-Coverage-Site-Id", "site-demo")
            addHeader("X-Shift-Coverage-Digest", body.sha256())
        }

        val response = controller.callback(request, "FAKE", body, signature, "fixture-v1", eventId.value, requestId)

        response.statusCode shouldBeEqualTo HttpStatus.ACCEPTED
        inbox.find(ShiftCoverageProvider.FAKE, eventId)?.status shouldBeEqualTo ShiftCoverageInboxStatus.RECEIVED
    }

    @Test
    fun `valid signature for a foreign aggregate is stale before inbox claim`() {
        val inbox = ShiftCoverageInboxService()
        val controller = ShiftCoverageCallbackController(inbox, Clock.fixed(issuedAt, ZoneOffset.UTC))
        val requestId = "request-foreign"
        val eventId = EventId("event-foreign")
        val context = ShiftCoverageSignatureContext(
            method = "POST", path = "/api/shift-coverage/callbacks/FAKE", schemaVersion = "v1",
            provider = ProviderName("FAKE"), requestId = requestId, datasetId = "dataset-demo",
            generationId = GenerationId("generation-demo"), aggregateId = PlanId("foreign-plan"),
            siteId = SiteId("site-demo"), eventId = eventId, issuedAt = issuedAt,
        )
        val signature = ShiftCoverageSignatureVerifier("shift-coverage-fixture-secret").sign(body, context, "fixture-v1")
        val request = request(eventId.value).apply {
            addHeader("X-Shift-Coverage-Issued-At", issuedAt.toString())
            addHeader("X-Shift-Coverage-Dataset-Id", "dataset-demo")
            addHeader("X-Shift-Coverage-Generation-Id", "generation-demo")
            addHeader("X-Shift-Coverage-Plan-Id", "foreign-plan")
            addHeader("X-Shift-Coverage-Site-Id", "site-demo")
            addHeader("X-Shift-Coverage-Digest", body.sha256())
        }

        val failure = assertFailsWith<ShiftCoverageHttpException> {
            controller.callback(request, "FAKE", body, signature, "fixture-v1", eventId.value, requestId)
        }

        failure.status shouldBeEqualTo HttpStatus.CONFLICT
        failure.code shouldBeEqualTo "STALE"
        inbox.find(ShiftCoverageProvider.FAKE, eventId) shouldBeEqualTo null
    }

    private fun request(eventId: String) = MockHttpServletRequest().apply {
        remoteAddr = "127.0.0.1"
        requestURI = "/api/shift-coverage/callbacks/FAKE"
        addHeader("X-Shift-Coverage-Event-Id", eventId)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
