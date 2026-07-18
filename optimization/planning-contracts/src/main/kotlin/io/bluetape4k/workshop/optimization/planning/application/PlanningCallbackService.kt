package io.bluetape4k.workshop.optimization.planning.application

import io.bluetape4k.workshop.optimization.planning.adapter.http.CallbackSignatureVerifier
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import io.bluetape4k.workshop.optimization.planning.persistence.CallbackOutcome
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAggregateRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAuditDecision
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAuditRecord
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAuditRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningCallbackInboxRecord
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningCallbackInboxRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestRepository
import io.bluetape4k.workshop.optimization.planning.observability.PlanningObservations
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class PlanningCallback(
    val provider: PlanningProvider,
    val eventId: String,
    val planningRequestId: UUID,
    val providerRevision: Long,
    val status: PlanningStatus,
    val scoreSummary: String,
    val constraintExplanations: List<String>,
) {
    init {
        require(eventId.matches(Regex("[A-Za-z0-9._:-]{1,200}"))) { "eventId has an invalid format" }
        require(providerRevision >= 0) { "providerRevision must not be negative" }
        require(scoreSummary.length <= 160) { "scoreSummary is too long" }
        require(constraintExplanations.size <= 20) { "too many constraint explanations" }
        require(constraintExplanations.all { it.length <= 240 }) { "constraint explanation is too long" }
    }
}

internal enum class PlanningCallbackDecision {
    ACCEPTED,
    DUPLICATE,
    STALE_REVISION,
    AGGREGATE_CHANGED,
    PROVIDER_MISMATCH,
    REJECTED,
}

internal class InvalidCallbackSignatureException: RuntimeException("invalid callback signature")

@Service
internal class PlanningCallbackService(
    private val signatureVerifier: CallbackSignatureVerifier,
    private val requestRepository: PlanningRequestRepository,
    private val aggregateRepository: PlanningAggregateRepository,
    private val inboxRepository: PlanningCallbackInboxRepository,
    private val auditRepository: PlanningAuditRepository,
    private val clock: Clock,
    private val observations: PlanningObservations,
) {

    @Transactional
    fun handle(
        callback: PlanningCallback,
        rawBody: ByteArray,
        signature: String?,
    ): PlanningCallbackDecision {
        if (!signatureVerifier.verify(callback.provider, rawBody, signature)) {
            throw InvalidCallbackSignatureException()
        }

        val inserted = inboxRepository.insertIfAbsent(
            PlanningCallbackInboxRecord(
                provider = callback.provider,
                eventId = callback.eventId,
                planningRequestId = callback.planningRequestId,
                providerRevision = callback.providerRevision,
                outcome = CallbackOutcome.RECEIVED,
            ),
        )
        if (!inserted) {
            return PlanningCallbackDecision.DUPLICATE.also(observations::recordCallback)
        }

        val request = requestRepository.findById(callback.planningRequestId)
        val explanation = redact(callback.constraintExplanations.joinToString("; "))
        val decision = when {
            callback.provider != request.provider ->
                PlanningCallbackDecision.PROVIDER_MISMATCH
            !aggregateRepository.versionMatches(request.aggregateId, request.aggregateVersion) ->
                PlanningCallbackDecision.AGGREGATE_CHANGED
            callback.status != PlanningStatus.SUCCEEDED ->
                PlanningCallbackDecision.REJECTED
            requestRepository.acceptIfNewer(
                callback.planningRequestId,
                callback.providerRevision,
                callback.scoreSummary,
                explanation,
            ) -> PlanningCallbackDecision.ACCEPTED
            else -> PlanningCallbackDecision.STALE_REVISION
        }

        auditRepository.append(
            PlanningAuditRecord(
                planningRequestId = callback.planningRequestId,
                callbackEventId = callback.eventId,
                aggregateVersion = request.aggregateVersion,
                providerRevision = callback.providerRevision,
                status = callback.status,
                scoreSummary = callback.scoreSummary.take(160),
                redactedExplanation = explanation,
                decision = decision.toAuditDecision(),
            ),
        )
        check(
            inboxRepository.markProcessed(
                callback.provider,
                callback.eventId,
                decision.toCallbackOutcome(),
                Instant.now(clock),
            ),
        ) { "callback inbox state was not updated" }
        return decision.also(observations::recordCallback)
    }

    private fun redact(value: String): String =
        value
            .replace(Regex("(?i)(secret|token|password|credential)[^\\s,;]*"), "[redacted]")
            .take(500)

    private fun PlanningCallbackDecision.toAuditDecision() = when (this) {
        PlanningCallbackDecision.ACCEPTED -> PlanningAuditDecision.ACCEPTED
        PlanningCallbackDecision.STALE_REVISION -> PlanningAuditDecision.STALE_REVISION
        PlanningCallbackDecision.AGGREGATE_CHANGED -> PlanningAuditDecision.AGGREGATE_CHANGED
        PlanningCallbackDecision.PROVIDER_MISMATCH -> PlanningAuditDecision.PROVIDER_MISMATCH
        PlanningCallbackDecision.REJECTED -> PlanningAuditDecision.REJECTED
        PlanningCallbackDecision.DUPLICATE -> error("duplicate callbacks are not audited")
    }

    private fun PlanningCallbackDecision.toCallbackOutcome() = when (this) {
        PlanningCallbackDecision.ACCEPTED -> CallbackOutcome.ACCEPTED
        PlanningCallbackDecision.STALE_REVISION -> CallbackOutcome.STALE_REVISION
        PlanningCallbackDecision.AGGREGATE_CHANGED -> CallbackOutcome.AGGREGATE_CHANGED
        PlanningCallbackDecision.PROVIDER_MISMATCH -> CallbackOutcome.PROVIDER_MISMATCH
        PlanningCallbackDecision.REJECTED -> CallbackOutcome.REJECTED
        PlanningCallbackDecision.DUPLICATE -> error("duplicate callbacks do not change inbox state")
    }
}
