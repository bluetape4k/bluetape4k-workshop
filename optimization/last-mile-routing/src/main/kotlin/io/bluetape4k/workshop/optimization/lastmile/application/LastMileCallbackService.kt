package io.bluetape4k.workshop.optimization.lastmile.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileAuditRecord
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileAuditRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileCallbackInboxRecord
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileCallbackInboxRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileRepository
import io.bluetape4k.workshop.optimization.lastmile.provider.CallbackDecision
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingCallback
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingCallbackCanonicalizer
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** 정규화 callback을 inbox에 먼저 기록한 뒤 proposal history로 승격합니다. */
@Service
internal class LastMileCallbackService(
    private val provider: RoutingProvider,
    private val inboxRepository: LastMileCallbackInboxRepository,
    private val repository: LastMileRepository,
    private val auditRepository: LastMileAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun handle(callback: RoutingCallback): CallbackDecision {
        if (!RoutingCallbackCanonicalizer.matches(callback)) {
            return recordDecision(callback, CallbackDecision.DIGEST_CONFLICT)
        }

        val inboxRecord = LastMileCallbackInboxRecord(
            provider = callback.provider,
            eventId = callback.eventId.value,
            requestId = callback.requestId,
            providerRevision = callback.providerRevision.value,
            payloadDigest = callback.payloadDigest,
            status = CallbackDecision.ACCEPTED.name,
            receivedAt = Instant.now(clock),
        )
        if (!inboxRepository.insertIfAbsent(inboxRecord)) {
            val stored = inboxRepository.find(callback.provider, callback.eventId.value)
            return recordDecision(
                callback,
                if (stored?.payloadDigest == callback.payloadDigest) {
                    CallbackDecision.DUPLICATE
                } else {
                    CallbackDecision.DIGEST_CONFLICT
                },
                writeAudit = false,
            )
        }

        val providerDecision = provider.acceptCallback(callback)
        if (providerDecision != CallbackDecision.ACCEPTED) {
            return recordDecision(callback, providerDecision)
        }

        val proposal = callback.result.proposal
        val existing = repository.loadProposal(proposal.planId, proposal.planRevision)
        val decision = when {
            existing?.providerRevision != null &&
                existing.providerRevision.value >= callback.providerRevision.value -> CallbackDecision.STALE_PROVIDER_REVISION

            existing == null -> {
                repository.saveProposal(proposal.copy(providerRevision = callback.providerRevision))
                CallbackDecision.ACCEPTED
            }

            existing.providerRevision != null -> {
                check(
                    repository.updateProposalProviderRevision(
                        planId = proposal.planId,
                        revision = proposal.planRevision,
                        expectedRevision = existing.providerRevision,
                        nextRevision = callback.providerRevision,
                    ),
                ) { "callback proposal provider revision was concurrently changed" }
                CallbackDecision.ACCEPTED
            }

            else -> {
                // A proposal without a provider revision is a local deterministic result.
                // Do not overwrite its route history with an external callback.
                CallbackDecision.STALE_PROVIDER_REVISION
            }
        }
        return recordDecision(callback, decision)
    }

    private fun recordDecision(
        callback: RoutingCallback,
        decision: CallbackDecision,
        writeAudit: Boolean = true,
    ): CallbackDecision {
        inboxRepository.updateStatus(callback.provider, callback.eventId.value, decision.name)
        if (writeAudit) {
            val proposal = callback.result.proposal
            auditRepository.append(
                LastMileAuditRecord(
                    planId = proposal.planId.value,
                    planRevision = proposal.planRevision,
                    decision = decision.name,
                    redactedSummary = "providerRevision=${callback.providerRevision.value}|hardScore=${proposal.score.hardScore}|softScore=${proposal.score.softScore}",
                ),
            )
        } else {
            log.warn { "last-mile callback replay was resolved without a second audit record; decision=${decision.name}" }
        }
        return decision
    }

    companion object : KLogging()
}
