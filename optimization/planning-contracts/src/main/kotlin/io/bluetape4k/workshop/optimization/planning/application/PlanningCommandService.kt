package io.bluetape4k.workshop.optimization.planning.application

import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAggregateRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.util.UUID

internal sealed interface PlanningCommandResult: Serializable {
    data class Ready(
        val requestId: UUID,
        val aggregateId: String,
        val aggregateVersion: Long,
        val acceptedRevision: Long,
    ): PlanningCommandResult {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    data class Conflict(val reason: String): PlanningCommandResult {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}

@Service
internal class PlanningCommandService(
    private val requestRepository: PlanningRequestRepository,
    private val aggregateRepository: PlanningAggregateRepository,
) {

    @Transactional(readOnly = true)
    fun createCandidate(requestId: UUID): PlanningCommandResult {
        val request = requestRepository.findById(requestId)
        if (!aggregateRepository.versionMatches(request.aggregateId, request.aggregateVersion)) {
            return PlanningCommandResult.Conflict("aggregate version changed")
        }
        val revision = request.acceptedRevision
            ?: return PlanningCommandResult.Conflict("planning result is not accepted")
        return PlanningCommandResult.Ready(
            requestId = request.id,
            aggregateId = request.aggregateId,
            aggregateVersion = request.aggregateVersion,
            acceptedRevision = revision,
        )
    }
}
