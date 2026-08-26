package io.bluetape4k.workshop.optimization.shiftcoverage.adapter

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ProviderName
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SnapshotDigest
import java.time.Instant
import org.junit.jupiter.api.Test

class ShiftCoverageSignatureVerifierTest {
    private val verifier = ShiftCoverageSignatureVerifier("shift-coverage-fixture-secret")
    private val context = ShiftCoverageSignatureContext(
        method = "POST",
        path = "/api/shift-coverage/callbacks/FAKE",
        schemaVersion = "v1",
        provider = ProviderName("FAKE"),
        requestId = "request-1",
        datasetId = "dataset-1",
        generationId = GenerationId("generation-1"),
        aggregateId = PlanId("plan-1"),
        siteId = SiteId("site-1"),
        eventId = EventId("event-1"),
        issuedAt = Instant.parse("2026-08-24T09:00:00Z"),
    )

    @Test
    fun `signature is bound to versioned context and canonical bytes`() {
        val body = "{\"ok\":true}".toByteArray()
        val signature = verifier.sign(body, context, keyVersion = "fixture-v1")

        verifier.verify(body, context, signature, keyVersion = "fixture-v1", now = context.issuedAt).shouldBeTrue()
        verifier.verify(body, context.copy(path = "/wrong"), signature, keyVersion = "fixture-v1", now = context.issuedAt).shouldBeFalse()
        verifier.verify(body, context, signature, keyVersion = "fixture-v1", now = context.issuedAt.plusSeconds(301)).shouldBeFalse()
    }
}
