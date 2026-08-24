package io.bluetape4k.workshop.optimization.lastmile.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileFailureCode
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlannerInput
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileOutboxRecord
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileOutboxRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileRepository
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingProvider
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingRequest
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingResult
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingSubmission
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal data class LastMileReplanCommand(
    val requestId: String,
    val input: LastMilePlannerInput,
) {
    init {
        require(requestId.matches(REQUEST_ID_PATTERN)) { "request id must be bounded" }
    }

    companion object {
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,96}")
    }
}

internal data class LastMileReplanReceipt(
    val submission: RoutingSubmission,
    val result: RoutingResult?,
)

internal class LastMileProviderUnavailableException : IllegalStateException(LastMileFailureCode.PROVIDER_UNAVAILABLE.name)

/** provider 호출과 proposal 저장 사이의 normalized lifecycle 경계입니다. */
@Service
internal class LastMileReplanService(
    private val provider: RoutingProvider,
    private val repository: LastMileRepository,
    private val outboxRepository: LastMileOutboxRepository,
    private val clock: Clock,
) {
    private val submissions = ConcurrentHashMap<String, RoutingSubmission>()

    @Transactional
    fun submit(command: LastMileReplanCommand): RoutingSubmission {
        submissions[command.requestId]?.let { return it }
        val request = RoutingRequest(command.requestId, command.input)
        val submission = try {
            provider.submit(request)
        } catch (failure: Exception) {
            log.warn { "last-mile provider submission failed; requestId=${command.requestId}, failure=${failure.javaClass.simpleName}" }
            throw LastMileProviderUnavailableException()
        }
        val existing = submissions.putIfAbsent(command.requestId, submission)
        if (existing != null) return existing
        outboxRepository.save(
            LastMileOutboxRecord(
                eventType = "ROUTING_SUBMITTED",
                payload = "requestId=${command.requestId}|provider=${submission.provider}|generation=${submission.requestGeneration}",
                status = "PENDING",
                attempts = 0,
                nextAttemptAt = Instant.now(clock),
                leaseOwner = null,
                leaseUntil = null,
            ),
        )
        return submission
    }

    @Transactional
    fun poll(submission: RoutingSubmission): RoutingResult? = try {
        provider.poll(submission)?.also { result ->
            val proposal = result.proposal.copy(providerRevision = result.providerRevision)
            val existing = repository.loadProposal(proposal.planId, proposal.planRevision)
            if (existing == null) {
                repository.saveProposal(proposal)
            } else if (existing.providerRevision == null) {
                repository.updateProposalProviderRevisionIfAbsent(
                    planId = proposal.planId,
                    revision = proposal.planRevision,
                    nextRevision = result.providerRevision,
                )
            } else if (existing.providerRevision.value < result.providerRevision.value) {
                repository.updateProposalProviderRevision(
                    planId = proposal.planId,
                    revision = proposal.planRevision,
                    expectedRevision = existing.providerRevision,
                    nextRevision = result.providerRevision,
                )
            }
        }
    } catch (failure: Exception) {
        log.warn { "last-mile provider poll failed; requestId=${submission.requestId}, failure=${failure.javaClass.simpleName}" }
        throw LastMileProviderUnavailableException()
    }

    @Transactional
    fun replan(command: LastMileReplanCommand): LastMileReplanReceipt {
        val submission = submit(command)
        return LastMileReplanReceipt(submission, poll(submission))
    }

    fun submission(requestId: String): RoutingSubmission? = submissions[requestId]

    companion object : KLogging()
}
