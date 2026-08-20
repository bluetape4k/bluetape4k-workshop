package io.bluetape4k.workshop.optimization.fieldservice.domain

import java.io.Serializable
import java.time.Duration
import java.time.Instant

/** synthetic worker의 UTC availability 구간입니다. */
data class AvailabilityWindow(
    val start: Instant,
    val end: Instant,
) : Serializable {
    init {
        require(start < end) { "availability window must have start before end" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 시작되었거나 수동 고정된 방문과 불변 route 위치입니다. */
data class VisitPin(
    val workerId: WorkerId,
    val routeOrder: Int,
) : Serializable {
    init {
        require(routeOrder >= 0) { "routeOrder must be non-negative" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** deterministic planner가 사용하는 synthetic worker record입니다. */
data class Worker(
    val workerId: WorkerId,
    val name: String,
    val skills: Set<Skill>,
    val availability: List<AvailabilityWindow>,
    val homeCoordinateId: CoordinateId? = null,
    val version: Long = 0L,
    val workerScheduleRevision: Long = 0L,
    val unavailable: Boolean = false,
) : Serializable {
    init {
        require(name.isNotBlank()) { "worker name must not be blank" }
        require(skills.size <= FieldServiceLimits.MAX_SKILLS_PER_WORKER) { "worker skills exceed configured limit" }
        require(availability.size <= FieldServiceLimits.MAX_AVAILABILITY_WINDOWS_PER_WORKER) {
            "worker availability exceeds configured limit"
        }
        require(version >= 0L) { "worker version must be non-negative" }
        require(workerScheduleRevision >= 0L) { "worker schedule revision must be non-negative" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** deterministic 정렬 규칙에 사용하는 방문 우선순위입니다. */
enum class VisitPriority {
    NORMAL,
    URGENT,
}

/** Field Service aggregate가 소유하는 lifecycle 상태입니다. */
enum class VisitStatus {
    UNASSIGNED,
    ASSIGNED,
    CANCELLED,
    NO_SHOW,
    COMPLETED,
}

/** planner가 소비하는 synthetic visit record입니다. */
data class Visit(
    val visitId: VisitId,
    val coordinateId: CoordinateId,
    val requiredSkill: Skill,
    val windowStart: Instant,
    val windowEnd: Instant,
    val serviceDuration: Duration,
    val priority: VisitPriority = VisitPriority.NORMAL,
    val status: VisitStatus = VisitStatus.UNASSIGNED,
    val version: Long = 0L,
    val startedAt: Instant? = null,
    val startedPin: VisitPin? = null,
    val manualPin: VisitPin? = null,
) : Serializable {
    init {
        require(windowStart < windowEnd) { "visit window must have start before end" }
        require(!serviceDuration.isNegative) { "service duration must be non-negative" }
        require(version >= 0L) { "visit version must be non-negative" }
        require(startedAt == null || startedPin != null) { "started visit must retain its pin" }
        require(startedAt == null || manualPin == null) { "started visit cannot have a manual pre-start pin" }
    }

    val effectivePin: VisitPin?
        get() = startedPin ?: manualPin

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** planner hard constraint의 닫힌 reason 집합입니다. */
enum class ConstraintReasonCode {
    MISSING_SKILL,
    UNAVAILABLE,
    TIME_WINDOW,
    TRAVEL_TIME,
    PIN_CONFLICT,
    CANCELLED,
    NO_SHOW,
    COMPLETED,
}

/** proposal lifecycle이며 committed dispatch assignment는 별도로 저장합니다. */
enum class PlanState {
    DRAFT,
    APPROVED,
    REJECTED,
    STALE,
}

/** read model에 노출하는 숫자 전용 score summary입니다. */
data class FieldServiceScoreSummary(
    val hardScore: Long,
    val softScore: Long,
    val assignedCount: Int,
    val unassignedCount: Int,
) : Serializable {
    init {
        require(assignedCount >= 0 && unassignedCount >= 0) { "score counts must be non-negative" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** worker의 deterministic route에 포함된 방문입니다. */
data class PlannedVisit(
    val visitId: VisitId,
    val coordinateId: CoordinateId,
    val routeOrder: Int,
    val pinned: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** plan proposal의 worker route 하나입니다. */
data class WorkerRoute(
    val workerId: WorkerId,
    val visits: List<PlannedVisit>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 할당되지 않은 방문의 명시적 reason입니다. */
data class UnassignedVisit(
    val visitId: VisitId,
    val reason: ConstraintReasonCode,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** plan에 보존하는 redacted closed explanation입니다. */
data class ConstraintExplanation(
    val visitId: VisitId,
    val reason: ConstraintReasonCode,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** proposal 생성 시 캡처한 version vector입니다. */
data class VersionVector(
    val visitVersions: Map<VisitId, Long>,
    val workerVersions: Map<WorkerId, Long>,
    val workerScheduleRevisions: Map<WorkerId, Long>,
) : Serializable {
    init {
        require(visitVersions.values.all { it >= 0L }) { "visit versions must be non-negative" }
        require(workerVersions.values.all { it >= 0L }) { "worker versions must be non-negative" }
        require(workerScheduleRevisions.values.all { it >= 0L }) { "schedule revisions must be non-negative" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** planner instrumentation이며 wall-clock 값은 hard gate로 사용하지 않습니다. */
data class PlannerMetrics(
    val candidateEvaluations: Int,
    val matrixLookups: Int,
    val externalCalls: Int,
    val invariants: List<String> = listOf("O(V*W+E)"),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 한 deterministic planner 실행에서 수집한 proposal과 instrumentation입니다. */
data class PlannerRun(
    val proposal: PlanProposal,
    val metrics: PlannerMetrics,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 불변 local plan proposal이며 provider free-form score text는 표현하지 않습니다. */
data class PlanProposal(
    val planId: PlanId,
    val planRevision: Long,
    val parentRevision: Long?,
    val providerRequestId: ProviderRequestId? = null,
    val providerRevision: Long? = null,
    val requestGeneration: Long,
    val datasetId: DatasetId,
    val versionVector: VersionVector,
    val routes: List<WorkerRoute>,
    val unassigned: List<UnassignedVisit>,
    val score: FieldServiceScoreSummary,
    val explanations: List<ConstraintExplanation> = emptyList(),
    val state: PlanState = PlanState.DRAFT,
) : Serializable {
    init {
        require(planRevision >= 0L) { "plan revision must be non-negative" }
        require(parentRevision == null || parentRevision >= 0L) { "parent revision must be non-negative" }
        require(requestGeneration >= 0L) { "request generation must be non-negative" }
        require(explanations.size <= FieldServiceLimits.MAX_EXPLANATIONS) { "too many explanations" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
