package io.bluetape4k.workshop.optimization.planning.application

import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

internal data class PlanningReadModel(
    val id: UUID,
    val aggregateId: String,
    val aggregateVersion: Long,
    val status: PlanningStatus,
    val provider: PlanningProvider,
    val providerRequestId: String?,
    val acceptedRevision: Long?,
    val scoreSummary: String?,
    val redactedExplanation: String?,
)

@Service
internal class PlanningQueryService(
    private val requestRepository: PlanningRequestRepository,
) {
    @Transactional(readOnly = true)
    fun find(requestId: UUID): PlanningReadModel {
        val request = requestRepository.findById(requestId)
        return PlanningReadModel(
            id = request.id,
            aggregateId = request.aggregateId,
            aggregateVersion = request.aggregateVersion,
            status = request.status,
            provider = request.provider,
            providerRequestId = request.providerRequestId,
            acceptedRevision = request.acceptedRevision,
            scoreSummary = request.scoreSummary,
            redactedExplanation = request.redactedExplanation,
        )
    }
}
