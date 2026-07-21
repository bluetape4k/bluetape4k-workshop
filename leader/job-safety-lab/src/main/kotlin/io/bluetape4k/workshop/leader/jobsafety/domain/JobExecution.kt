package io.bluetape4k.workshop.leader.jobsafety.domain

import java.io.Serializable
import java.time.Instant

enum class JobExecutionState {
    REQUESTED,
    LEADER_ACQUIRED,
    FENCE_ACQUIRED,
    RUNNING,
    COMMITTED,
    EFFECT_PENDING,
    RECONCILIATION_REQUIRED,
    COMPLETED,
    SKIPPED,
    REJECTED,
    FAILED,
}

enum class JobRejectionReason {
    LEADER_CONTENDED,
    FENCE_CONTENDED,
    STALE_FENCE,
    STALE_MEMBERSHIP,
    WRONG_REGION,
    INCOMPATIBLE_VERSION,
    STALE_NAMESPACE,
    FENCE_BACKEND_FAILURE,
    DOMAIN_FAILURE,
}

data class JobRunRequest(
    val jobName: JobName,
    val tenantId: TenantId,
    val conflictKey: ConflictKey,
    val fencingOwnerId: FencingOwnerId,
    val membershipRevision: MembershipRevision,
    val regionId: RegionId,
    val regionEpoch: RegionEpoch,
    val namespaceEpoch: NamespaceEpoch,
    val contractVersion: ExecutionContractVersion,
    val operationId: OperationId,
    val nextValue: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class JobTimelineEvent(
    val state: JobExecutionState,
    val occurredAt: Instant,
    val rejection: JobRejectionReason? = null,
    val detailCode: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class JobExecutionSnapshot(
    val request: JobRunRequest,
    val state: JobExecutionState,
    val fencingToken: FencingToken? = null,
    val rejection: JobRejectionReason? = null,
    val timeline: List<JobTimelineEvent> = emptyList(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class JobRunResult(
    val state: JobExecutionState,
    val fencingToken: FencingToken? = null,
    val rejection: JobRejectionReason? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
