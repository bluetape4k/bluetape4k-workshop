package io.bluetape4k.workshop.optimization.fieldservice.adapter.fake

import io.bluetape4k.workshop.optimization.fieldservice.adapter.http.FieldServiceCallbackEnvelope
import io.bluetape4k.workshop.optimization.fieldservice.adapter.http.FieldServiceCanonicalizer
import io.bluetape4k.workshop.optimization.fieldservice.adapter.http.FieldServiceProvider
import io.bluetape4k.workshop.optimization.fieldservice.adapter.http.FixtureFieldServiceSignatureVerifier

/** #524 lifecycle와 local callback seam의 재현 가능한 fixture metadata입니다. */
data class FieldServicePlanningFixtureMetadata(
    val planningContractsCommit: String = "80c1f95",
    val fixtureVersion: String = "field-service-planning-fixture-v1",
) {
    init {
        require(planningContractsCommit.matches(Regex("[0-9a-f]{7,40}"))) {
            "planning-contracts commit must be a lowercase git prefix"
        }
        require(fixtureVersion == "field-service-planning-fixture-v1") {
            "unsupported Field Service fixture version"
        }
    }
}
/** 외부 provider 대신 canonical body와 fixture signature를 재생하는 경로입니다. */
class FieldServicePlanningFixture(
    val metadata: FieldServicePlanningFixtureMetadata = FieldServicePlanningFixtureMetadata(),
    private val canonicalizer: FieldServiceCanonicalizer = FieldServiceCanonicalizer(),
    private val signatureVerifier: FixtureFieldServiceSignatureVerifier = FixtureFieldServiceSignatureVerifier(),
) {
    fun parseCallback(body: ByteArray): FieldServiceCallbackEnvelope =
        FieldServiceCallbackEnvelope.parse(body, canonicalizer)

    fun sign(provider: FieldServiceProvider, body: ByteArray): String =
        signatureVerifier.sign(provider, canonicalizer.canonicalBytes(body))

    fun verifyMetadata(expected: FieldServicePlanningFixtureMetadata) {
        require(metadata == expected) { "fixture metadata does not match the pinned contract" }
    }
}
