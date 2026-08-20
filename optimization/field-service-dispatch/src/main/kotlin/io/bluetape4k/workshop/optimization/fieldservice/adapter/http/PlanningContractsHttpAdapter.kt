package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigestMatch
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
import io.bluetape4k.workshop.optimization.fieldservice.domain.ProviderRequestId

/** #524 planning lifecycle을 선택적으로 감싸는 transport port입니다. assignment 권위는 갖지 않습니다. */
fun interface PlanningContractsTransport {
    fun submit(request: PlanningContractsSubmission): PlanningContractsSubmissionResult
}

/** Provider로 보낼 수 있는 최소 normalized request입니다. raw 기준 데이터는 포함하지 않습니다. */
data class PlanningContractsSubmission(
    val aggregateId: String,
    val aggregateVersion: Long,
    val datasetId: DatasetId,
    val provider: FieldServiceProvider,
) {
    init {
        require(aggregateId.isNotBlank()) { "aggregateId must not be blank" }
        require(aggregateId.length <= FieldServiceLimits.MAX_STRING_LENGTH) {
            "aggregateId exceeds ${FieldServiceLimits.MAX_STRING_LENGTH} characters"
        }
        require(aggregateVersion >= 0L) { "aggregateVersion must be non-negative" }
        require(provider != FieldServiceProvider.UNKNOWN) { "provider must be known" }
    }
}

/** #524 lifecycle response에서 local binding에 필요한 식별자만 받습니다. */
data class PlanningContractsSubmissionResult(
    val providerRequestId: ProviderRequestId,
    val status: String = "QUEUED",
)

/** Callback preflight와 optional lifecycle transport를 한 경계로 묶습니다. */
class PlanningContractsHttpAdapter(
    private val transport: PlanningContractsTransport,
    private val callbackState: FieldServiceCallbackState,
    private val signatureVerifier: FieldServiceSignatureVerifier = FieldServiceSignatureVerifier.fixture(),
    private val canonicalizer: FieldServiceCanonicalizer = FieldServiceCanonicalizer(),
) {
    private val callbackMutex = Any()
    fun submit(
        aggregateId: String,
        aggregateVersion: Long,
        datasetId: DatasetId,
        provider: FieldServiceProvider,
    ): PlanningContractsSubmissionResult = transport.submit(
        PlanningContractsSubmission(
            aggregateId = aggregateId,
            aggregateVersion = aggregateVersion,
            datasetId = datasetId,
            provider = provider,
        ),
    )

    fun submit(
        aggregateId: AggregateId,
        aggregateVersion: Long,
        datasetId: DatasetId,
        provider: FieldServiceProvider,
    ): PlanningContractsSubmissionResult = submit(aggregateId.value, aggregateVersion, datasetId, provider)

    /** Body/signature가 없는 호출은 unsigned로만 분류하며 local state를 쓰지 않습니다. */
    fun preflight(envelope: FieldServiceCallbackEnvelope): FieldServiceCallbackDecision =
        preflight(envelope, ByteArray(0), null).decision

    /** Canonical body와 명시적 signature를 검증한 뒤 binding과 revision을 확인합니다. */
    fun preflight(
        envelope: FieldServiceCallbackEnvelope,
        rawBody: ByteArray,
        signature: String?,
    ): FieldServiceCallbackResult {
        if (envelope.provider == FieldServiceProvider.UNKNOWN) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.UNKNOWN_PROVIDER)
        }
        if (rawBody.isEmpty() || signature.isNullOrBlank()) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.UNSIGNED)
        }

        val canonicalBody = try {
            canonicalizer.canonicalBytes(rawBody)
        } catch (_: InvalidFieldServiceInput) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.INVALID_ENVELOPE)
        }
        if (!signatureVerifier.verify(envelope.provider, canonicalBody, signature)) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.INVALID_SIGNATURE)
        }

        val parsed = try {
            FieldServiceCallbackEnvelope.parse(rawBody, canonicalizer)
        } catch (_: InvalidFieldServiceInput) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.INVALID_ENVELOPE)
        }
        if (parsed != envelope) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.INVALID_ENVELOPE)
        }

        val binding = callbackState.binding(envelope.planId)
            ?: return FieldServiceCallbackResult(FieldServiceCallbackDecision.PLAN_MISMATCH)
        if (binding.provider != envelope.provider) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.PROVIDER_MISMATCH)
        }
        if (binding.planningRequestId != envelope.planningRequestId) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.PLANNING_REQUEST_MISMATCH)
        }
        if (binding.providerRequestId != envelope.providerRequestId) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.PROVIDER_REQUEST_MISMATCH)
        }
        if (binding.datasetId != envelope.datasetId) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.DATASET_MISMATCH)
        }
        if (envelope.requestGeneration < binding.requestGeneration) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.STALE_REQUEST_GENERATION)
        }
        if (envelope.requestGeneration > binding.requestGeneration) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.REQUEST_GENERATION_MISMATCH)
        }
        if (envelope.status != FieldServiceCallbackStatus.SUCCEEDED) {
            return FieldServiceCallbackResult(FieldServiceCallbackDecision.REJECTED)
        }

        val digest = canonicalizer.digest(rawBody)
        callbackState.eventDigest(envelope.eventId)?.let { stored ->
            return if (canonicalizer.compareStoredDigest(stored, digest) == EventDigestMatch.DUPLICATE) {
                FieldServiceCallbackResult(FieldServiceCallbackDecision.DUPLICATE)
            } else {
                FieldServiceCallbackResult(FieldServiceCallbackDecision.EVENT_KEY_REUSED)
            }
        }
        callbackState.latestRevision(envelope.provider, envelope.providerRequestId)?.let { latest ->
            if (envelope.providerRevision <= latest) {
                return FieldServiceCallbackResult(
                    decision = FieldServiceCallbackDecision.STALE_REVISION,
                    auditOnly = true,
                )
            }
        }
        return FieldServiceCallbackResult(FieldServiceCallbackDecision.ACCEPTED)
    }

    /** unsigned overload는 fail-closed 하며, 정상 기록은 raw body 경로에서만 수행합니다. */
    fun acceptCallback(envelope: FieldServiceCallbackEnvelope): FieldServiceCallbackResult =
        acceptCallback(envelope, ByteArray(0), null)

    /** Preflight가 통과한 callback만 한 번 기록합니다. */
    fun acceptCallback(
        envelope: FieldServiceCallbackEnvelope,
        rawBody: ByteArray,
        signature: String?,
    ): FieldServiceCallbackResult = synchronized(callbackMutex) {
        val result = preflight(envelope, rawBody, signature)
        when (result.decision) {
            FieldServiceCallbackDecision.ACCEPTED -> {
                callbackState.accept(envelope, canonicalizer.digest(rawBody))
                result.copy(stateChanged = true)
            }
            FieldServiceCallbackDecision.STALE_REVISION,
            FieldServiceCallbackDecision.STALE_REQUEST_GENERATION,
            -> {
                callbackState.audit(result.decision, envelope)
                result.copy(auditOnly = true)
            }
            else -> result
        }
    }
}
