package io.bluetape4k.workshop.optimization.lastmile.application

import io.bluetape4k.workshop.optimization.lastmile.domain.CarrierVersion
import io.bluetape4k.workshop.optimization.lastmile.domain.JobId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanProposal
import io.bluetape4k.workshop.optimization.lastmile.domain.PlanState
import io.bluetape4k.workshop.optimization.lastmile.domain.StopKind
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileAuditRecord
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileAuditRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileCommittedStopRecord
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileCommittedStopRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileOutboxRecord
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileOutboxRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

internal data class LastMileApprovalCommand(
    val planId: LastMilePlanId,
    val planRevision: Long,
    val expectedMatrixRevision: Long,
    val expectedCarrierVersions: Map<JobId, CarrierVersion> = emptyMap(),
)

internal enum class LastMileApprovalResult {
    COMMITTED,
    STALE_ROUTE_APPROVAL,
}

/** proposal을 committed route로 승격하는 단일 PostgreSQL transaction 경계입니다. */
@Service
internal class LastMileApprovalService(
    private val repository: LastMileRepository,
    private val committedStopRepository: LastMileCommittedStopRepository,
    private val outboxRepository: LastMileOutboxRepository,
    private val auditRepository: LastMileAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun approve(command: LastMileApprovalCommand): LastMileApprovalResult {
        val proposal = repository.loadProposal(command.planId, command.planRevision)
        if (proposal == null || !isCurrent(proposal, command)) {
            audit(command, LastMileApprovalResult.STALE_ROUTE_APPROVAL, "version_or_pin_conflict")
            return LastMileApprovalResult.STALE_ROUTE_APPROVAL
        }

        if (!repository.approveProposal(command.planId, command.planRevision)) {
            audit(command, LastMileApprovalResult.STALE_ROUTE_APPROVAL, "plan_state_conflict")
            return LastMileApprovalResult.STALE_ROUTE_APPROVAL
        }

        val committedAt = Instant.now(clock)
        proposal.routes.forEach { route ->
            route.stops.forEach { stop ->
                committedStopRepository.save(
                    LastMileCommittedStopRecord(
                        jobId = stop.jobId.value,
                        planId = proposal.planId.value,
                        planRevision = proposal.planRevision,
                        vehicleId = route.vehicleId.value,
                        kind = stop.kind.name,
                        sequence = stop.sequence,
                        carrierVersion = proposal.carrierVersions[stop.jobId]?.value ?: 0L,
                        committedAt = committedAt,
                    ),
                )
            }
        }
        check(repository.commitProposal(command.planId, command.planRevision)) {
            "approved plan could not be committed"
        }
        outboxRepository.save(
            LastMileOutboxRecord(
                eventType = "ROUTE_COMMITTED",
                payload = "planId=${command.planId.value}|planRevision=${command.planRevision}",
                status = "PENDING",
                attempts = 0,
                nextAttemptAt = committedAt,
                leaseOwner = null,
                leaseUntil = null,
            ),
        )
        audit(command, LastMileApprovalResult.COMMITTED, "committed_route")
        return LastMileApprovalResult.COMMITTED
    }

    private fun isCurrent(proposal: LastMilePlanProposal, command: LastMileApprovalCommand): Boolean {
        if (proposal.state != PlanState.PROPOSED || proposal.matrixRevision != command.expectedMatrixRevision) return false

        val jobs = repository.findJobs().associateBy { it.jobId }
        val expectedVersions = proposal.carrierVersions
        if (command.expectedCarrierVersions.any { (jobId, version) -> expectedVersions[jobId] != version }) return false
        if (expectedVersions.any { (jobId, version) ->
                val current = jobs[jobId]
                current == null || current.carrierVersion != version || current.status != io.bluetape4k.workshop.optimization.lastmile.domain.JobStatus.OPEN
            }
        ) return false

        val startedByVehicle = repository.findVehicles().associateBy { it.vehicleId }
        if (proposal.routes.any { route ->
                val started = startedByVehicle[route.vehicleId]?.startedStop
                route.stops.filter { it.pinned }.any { pinned ->
                    started == null || started.jobId != pinned.jobId || started.kind != pinned.kind ||
                        started.coordinateId != pinned.coordinateId || started.sequence != pinned.sequence
                }
            }
        ) return false

        return proposal.routes.flatMap { it.stops }.groupBy { it.jobId }.all { (_, stops) ->
            val pickup = stops.firstOrNull { it.kind == StopKind.PICKUP }
            val delivery = stops.firstOrNull { it.kind == StopKind.DELIVERY }
            pickup == null || delivery == null || pickup.sequence < delivery.sequence
        }
    }

    private fun audit(command: LastMileApprovalCommand, result: LastMileApprovalResult, summary: String) {
        auditRepository.append(
            LastMileAuditRecord(
                planId = command.planId.value,
                planRevision = command.planRevision,
                decision = result.name,
                redactedSummary = summary,
            ),
        )
    }
}
