package io.bluetape4k.workshop.optimization.planning.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAggregateRecord
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningAggregateRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxRecord
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxRepository
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestRecord
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class CreatePlanningRequest(
    val aggregateId: String,
    val aggregateVersion: Long,
    val datasetId: String,
    val parentRevision: Long?,
    val provider: PlanningProvider,
) {
    init {
        require(IDENTIFIER.matches(aggregateId)) { "aggregateId has an invalid format" }
        require(IDENTIFIER.matches(datasetId)) { "datasetId has an invalid format" }
        require(aggregateVersion >= 0) { "aggregateVersion must not be negative" }
        require(parentRevision == null || parentRevision >= 0) { "parentRevision must not be negative" }
    }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,160}")
    }
}

@Service
internal class PlanningRequestService(
    private val aggregateRepository: PlanningAggregateRepository,
    private val requestRepository: PlanningRequestRepository,
    private val outboxRepository: PlanningOutboxRepository,
    private val clock: Clock,
    private val planningEngine: io.bluetape4k.workshop.optimization.planning.domain.PlanningEngine,
) {

    @Transactional
    fun create(
        command: CreatePlanningRequest,
        requestId: UUID = Uuid.V7.nextId(),
    ): PlanningRequestRecord {
        require(command.provider == planningEngine.provider) {
            "provider ${command.provider} is not active"
        }
        ensureAggregateVersion(command)

        val request = requestRepository.save(
            PlanningRequestRecord(
                id = requestId,
                aggregateId = command.aggregateId,
                aggregateVersion = command.aggregateVersion,
                datasetId = command.datasetId,
                parentRevision = command.parentRevision,
                status = PlanningStatus.QUEUED,
                provider = command.provider,
            ),
        )
        outboxRepository.save(
            PlanningOutboxRecord(
                planningRequestId = requestId,
                payload = closedPayload(command, requestId),
                nextAttemptAt = Instant.now(clock),
            ),
        )
        return request
    }

    private fun ensureAggregateVersion(command: CreatePlanningRequest) {
        val existing = aggregateRepository.findByAggregateId(command.aggregateId)
        if (existing == null) {
            aggregateRepository.save(
                PlanningAggregateRecord(
                    aggregateId = command.aggregateId,
                    version = command.aggregateVersion,
                ),
            )
        } else {
            check(existing.version == command.aggregateVersion) {
                "aggregate version changed"
            }
        }
    }

    private fun closedPayload(command: CreatePlanningRequest, requestId: UUID): String =
        """{"requestId":"$requestId","datasetId":"${command.datasetId}","aggregateId":"${command.aggregateId}","aggregateVersion":${command.aggregateVersion}}"""
}
