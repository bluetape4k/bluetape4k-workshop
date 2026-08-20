package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.fieldservice.adapter.fake.FieldServicePlanningFixture
import io.bluetape4k.workshop.optimization.fieldservice.adapter.fake.FieldServicePlanningFixtureMetadata
import io.bluetape4k.workshop.optimization.fieldservice.domain.ConstraintReasonCode
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceScoreSummary
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.ProviderRequestId
import io.bluetape4k.workshop.optimization.fieldservice.domain.VersionVector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

class FieldServiceCallbackEnvelopeTest {
    private val canonicalizer = FieldServiceCanonicalizer()
    private val signatureVerifier = FixtureFieldServiceSignatureVerifier()

    @Test
    fun `strict parser accepts numeric score and closed reason codes only`() {
        val envelope = FieldServiceCallbackEnvelope.parse(successBody())

        envelope.provider shouldBeEqualTo FieldServiceProvider.FAKE
        envelope.scoreSummary shouldBeEqualTo FieldServiceScoreSummary(10, -2, 1, 0)
        envelope.constraintExplanations.single().reason shouldBeEqualTo ConstraintReasonCode.TIME_WINDOW
    }

    @Test
    fun `strict parser rejects unknown fields and provider text leakage`() {
        assertThrows<IllegalArgumentException> {
            FieldServiceCallbackEnvelope.parse(successBody(extraField = ",\"unknown\":true"))
        }
        assertThrows<IllegalArgumentException> {
            FieldServiceCallbackEnvelope.parse(
                """{"provider":"FAKE","eventId":"event-1","planningRequestId":"request-1","providerRequestId":"provider-request","providerRevision":1,"requestGeneration":1,"planId":"plan-1","datasetId":"dataset-1","status":"SUCCEEDED","scoreSummary":{"hardScore":0,"softScore":0,"assignedCount":0,"unassignedCount":0,"secret":"token=abc"},"constraintExplanations":[]}""".toByteArray(),
            )
        }
        assertThrows<IllegalArgumentException> {
            FieldServiceCallbackEnvelope.parse(
                """{"provider":"FAKE","eventId":"event-1","planningRequestId":"request-1","providerRequestId":"provider-request","providerRevision":1,"requestGeneration":1,"planId":"plan-1","datasetId":"dataset-1","status":"SUCCEEDED","scoreSummary":{"hardScore":0,"softScore":0,"assignedCount":0,"unassignedCount":0},"constraintExplanations":[{"visitId":"visit-1","reason":"raw provider address"}]}""".toByteArray(),
            )
        }
        assertThrows<IllegalArgumentException> {
            FieldServiceCallbackEnvelope.parse(
                """{"provider":"FAKE","eventId":"event-1","planningRequestId":"request-1","providerRequestId":"provider-request","providerRevision":1,"requestGeneration":1,"planId":"plan-1","datasetId":"dataset-1","status":"SUCCEEDED","scoreSummary":{"hardScore":0,"softScore":0,"assignedCount":0,"unassignedCount":NaN},"constraintExplanations":[]}""".toByteArray(),
            )
        }
    }

    @Test
    fun `signature and binding failures do not invoke transport or mutate callback state`() {
        val state = InMemoryFieldServiceCallbackState()
        state.register(binding())
        val calls = AtomicInteger()
        val adapter = adapter(state) { calls.incrementAndGet() }
        val body = successBody()
        val envelope = FieldServiceCallbackEnvelope.parse(body)

        val result = adapter.acceptCallback(envelope, body, "wrong-signature")

        result.decision shouldBeEqualTo FieldServiceCallbackDecision.INVALID_SIGNATURE
        result.stateChanged.shouldBeFalse()
        state.acceptedCount() shouldBeEqualTo 0
        calls.get() shouldBeEqualTo 0
    }

    @Test
    fun `stale revision is audit only while superseded generation is rejected`() {
        val state = InMemoryFieldServiceCallbackState()
        state.register(binding())
        val adapter = adapter(state)
        val firstBody = successBody()
        val first = FieldServiceCallbackEnvelope.parse(firstBody)
        adapter.acceptCallback(first, firstBody, sign(firstBody)).decision shouldBeEqualTo
            FieldServiceCallbackDecision.ACCEPTED

        val staleRevisionBody = successBody(eventId = "event-stale", providerRevision = 0)
        val staleRevision = FieldServiceCallbackEnvelope.parse(staleRevisionBody)
        val staleResult = adapter.acceptCallback(staleRevision, staleRevisionBody, sign(staleRevisionBody))
        staleResult.decision shouldBeEqualTo FieldServiceCallbackDecision.STALE_REVISION
        staleResult.auditOnly.shouldBeTrue()
        state.acceptedCount() shouldBeEqualTo 1
        state.auditCount(FieldServiceCallbackDecision.STALE_REVISION) shouldBeEqualTo 1

        val staleGenerationBody = successBody(eventId = "event-generation-stale", requestGeneration = 0)
        val staleGeneration = FieldServiceCallbackEnvelope.parse(staleGenerationBody)
        val generationResult = adapter.acceptCallback(staleGeneration, staleGenerationBody, sign(staleGenerationBody))
        generationResult.decision shouldBeEqualTo FieldServiceCallbackDecision.STALE_REQUEST_GENERATION
        generationResult.stateChanged.shouldBeFalse()
        state.acceptedCount() shouldBeEqualTo 1
    }

    @Test
    fun `provider request plan and dataset mismatches are fail closed`() {
        val state = InMemoryFieldServiceCallbackState()
        state.register(binding())
        val adapter = adapter(state)

        val mismatched = successBody(providerRequestId = "other-request")
        val envelope = FieldServiceCallbackEnvelope.parse(mismatched)
        val result = adapter.acceptCallback(envelope, mismatched, sign(mismatched))

        result.decision shouldBeEqualTo FieldServiceCallbackDecision.PROVIDER_REQUEST_MISMATCH
        result.stateChanged.shouldBeFalse()
        state.acceptedCount() shouldBeEqualTo 0
        state.auditCount(FieldServiceCallbackDecision.PROVIDER_REQUEST_MISMATCH) shouldBeEqualTo 0
    }

    @Test
    fun `unknown provider is rejected before callback state lookup`() {
        val state = InMemoryFieldServiceCallbackState()
        state.register(binding())
        val adapter = adapter(state)
        val body = successBody(provider = "UNKNOWN_VENDOR")
        val envelope = FieldServiceCallbackEnvelope.parse(body)

        val result = adapter.acceptCallback(envelope, body, sign(body))

        result.decision shouldBeEqualTo FieldServiceCallbackDecision.UNKNOWN_PROVIDER
        result.stateChanged.shouldBeFalse()
        state.acceptedCount() shouldBeEqualTo 0
    }

    @Test
    fun `same event and digest is a no-op while changed digest is a conflict`() {
        val state = InMemoryFieldServiceCallbackState()
        state.register(binding())
        val adapter = adapter(state)
        val firstBody = successBody()
        val first = FieldServiceCallbackEnvelope.parse(firstBody)
        adapter.acceptCallback(first, firstBody, sign(firstBody)).decision shouldBeEqualTo
            FieldServiceCallbackDecision.ACCEPTED

        adapter.acceptCallback(first, firstBody, sign(firstBody)).decision shouldBeEqualTo
            FieldServiceCallbackDecision.DUPLICATE
        val changedBody = successBody(providerRevision = 2, scoreHardScore = 11)
        val changed = FieldServiceCallbackEnvelope.parse(changedBody)
        adapter.acceptCallback(changed, changedBody, sign(changedBody)).decision shouldBeEqualTo
            FieldServiceCallbackDecision.EVENT_KEY_REUSED
        state.acceptedCount() shouldBeEqualTo 1
    }

    @Test
    fun `concurrent callback acceptance records one event atomically`() {
        val state = InMemoryFieldServiceCallbackState()
        state.register(binding())
        val adapter = adapter(state)
        val body = successBody()
        val envelope = FieldServiceCallbackEnvelope.parse(body)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                executor.submit<FieldServiceCallbackDecision> {
                    adapter.acceptCallback(envelope, body, sign(body)).decision
                }
            }.map { it.get() }

            results.count { it == FieldServiceCallbackDecision.ACCEPTED } shouldBeEqualTo 1
            results.count { it == FieldServiceCallbackDecision.DUPLICATE } shouldBeEqualTo 1
            state.acceptedCount() shouldBeEqualTo 1
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `fixture metadata and signature are pinned to the approved seam`() {
        val fixture = FieldServicePlanningFixture()
        fixture.verifyMetadata(FieldServicePlanningFixtureMetadata("80c1f95", "field-service-planning-fixture-v1"))
        val body = successBody()
        val signature = fixture.sign(FieldServiceProvider.FAKE, body)
        signatureVerifier.verify(
            FieldServiceProvider.FAKE,
            canonicalizer.canonicalBytes(body),
            signature,
        ).shouldBeTrue()
    }

    @Test
    fun `submission mapping keeps only the planning contract fields`() {
        var received: PlanningContractsSubmission? = null
        val adapter = PlanningContractsHttpAdapter(
            transport = PlanningContractsTransport { request ->
                received = request
                PlanningContractsSubmissionResult(ProviderRequestId("provider-request"))
            },
            callbackState = InMemoryFieldServiceCallbackState(),
            signatureVerifier = signatureVerifier,
        )

        adapter.submit(
            aggregateId = "aggregate-1",
            aggregateVersion = 4,
            datasetId = DatasetId("dataset-1"),
            provider = FieldServiceProvider.FAKE,
        )

        received?.aggregateId shouldBeEqualTo "aggregate-1"
        received?.aggregateVersion shouldBeEqualTo 4L
        received?.datasetId shouldBeEqualTo DatasetId("dataset-1")
        received?.provider shouldBeEqualTo FieldServiceProvider.FAKE
    }

    private fun adapter(
        state: InMemoryFieldServiceCallbackState,
        onSubmit: () -> Unit = {},
    ) = PlanningContractsHttpAdapter(
        transport = PlanningContractsTransport {
            onSubmit()
            PlanningContractsSubmissionResult(ProviderRequestId("provider-request"))
        },
        callbackState = state,
        signatureVerifier = signatureVerifier,
    )

    private fun binding() = FieldServiceCallbackBinding(
        provider = FieldServiceProvider.FAKE,
        planningRequestId = "request-1",
        providerRequestId = ProviderRequestId("provider-request"),
        requestGeneration = 1,
        planId = PlanId("plan-1"),
        datasetId = DatasetId("dataset-1"),
        versionVector = VersionVector(emptyMap(), emptyMap(), emptyMap()),
    )

    private fun sign(body: ByteArray): String =
        signatureVerifier.sign(FieldServiceProvider.FAKE, canonicalizer.canonicalBytes(body))

    private fun sign(body: String): String = sign(body.toByteArray())

    private fun successBody(
        provider: String = "FAKE",
        eventId: String = "event-1",
        providerRequestId: String = "provider-request",
        providerRevision: Long = 1,
        requestGeneration: Long = 1,
        scoreHardScore: Long = 10,
        extraField: String = "",
    ) = """{"provider":"$provider","eventId":"$eventId","planningRequestId":"request-1","providerRequestId":"$providerRequestId","providerRevision":$providerRevision,"requestGeneration":$requestGeneration,"planId":"plan-1","datasetId":"dataset-1","status":"SUCCEEDED","scoreSummary":{"hardScore":$scoreHardScore,"softScore":-2,"assignedCount":1,"unassignedCount":0},"constraintExplanations":[{"visitId":"visit-1","reason":"TIME_WINDOW"}]$extraField}""".toByteArray()
}
