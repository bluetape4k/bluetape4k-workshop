package io.bluetape4k.workshop.optimization.shiftcoverage.domain

import java.io.Serializable
import java.time.Duration
import java.time.Instant

/** UTC interval이며 종료 시각은 시작 시각보다 뒤에 있어야 합니다. */
data class TimeInterval(val startAt: Instant, val endAt: Instant) : Serializable {
    init {
        if (!startAt.isBefore(endAt)) throw InvalidShiftCoverageInput("time interval must have start before end")
    }
    companion object { private const val serialVersionUID: Long = 1L }
}

/** worker preference는 닫힌 signed minor-unit 점수입니다. */
data class WorkerPreference(val skill: Skill, val weightMinor: Long) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

/** worker가 가진 skill 토큰입니다. */
@JvmInline
value class Skill(val value: String) : Serializable {
    init {
        if (value.isBlank() || value.length > ShiftCoverageLimits.MAX_STRING_LENGTH) {
            throw InvalidShiftCoverageInput("skill must be non-blank and <= ${ShiftCoverageLimits.MAX_STRING_LENGTH} characters")
        }
    }
}

/** synthetic multi-site worker record입니다. */
data class ShiftWorker(
    val workerId: WorkerId,
    val siteId: SiteId,
    val displayName: String,
    val skills: Set<Skill>,
    val availability: List<TimeInterval>,
    val preferences: List<WorkerPreference> = emptyList(),
    val revision: Long = 0L,
    val scheduleRevision: Long = 0L,
    val sickCalled: Boolean = false,
) : Serializable {
    init {
        if (displayName.isBlank() || displayName.length > ShiftCoverageLimits.MAX_STRING_LENGTH) {
            throw InvalidShiftCoverageInput("worker displayName is outside the allowed range")
        }
        if (skills.size > ShiftCoverageLimits.MAX_SKILLS) throw InvalidShiftCoverageInput("too many worker skills")
        if (availability.size > ShiftCoverageLimits.MAX_AVAILABILITY_WINDOWS) {
            throw InvalidShiftCoverageInput("too many worker availability windows")
        }
        if (preferences.size > ShiftCoverageLimits.MAX_PREFERENCES) throw InvalidShiftCoverageInput("too many worker preferences")
        if (revision < 0L || scheduleRevision < 0L) throw InvalidShiftCoverageInput("worker revisions must be non-negative")
    }
    companion object { private const val serialVersionUID: Long = 1L }
}

/** demand와 hard rule을 포함한 synthetic shift입니다. */
data class Shift(
    val shiftId: ShiftId,
    val siteId: SiteId,
    val startAt: Instant,
    val endAt: Instant,
    val requiredSkills: Set<Skill>,
    val demand: Int = 1,
    val preference: WorkerPreference? = null,
    val revision: Long = 0L,
    val startedAt: Instant? = null,
    val pinnedWorkerId: WorkerId? = null,
) : Serializable {
    init {
        if (!startAt.isBefore(endAt)) throw InvalidShiftCoverageInput("shift must have start before end")
        if (demand <= 0) throw InvalidShiftCoverageInput("shift demand must be positive")
        if (requiredSkills.size > ShiftCoverageLimits.MAX_SKILLS) throw InvalidShiftCoverageInput("too many required skills")
        if (revision < 0L) throw InvalidShiftCoverageInput("shift revision must be non-negative")
    }
    val duration: Duration get() = Duration.between(startAt, endAt)
    val isStarted: Boolean get() = startedAt != null
    companion object { private const val serialVersionUID: Long = 1L }
}

/** assignment는 planner가 제안하고 approval/swap만 변경할 수 있습니다. */
data class ShiftAssignment(
    val assignmentId: AssignmentId,
    val siteId: SiteId,
    val shiftId: ShiftId,
    val workerId: WorkerId,
    val revision: Long = 0L,
    val pinned: Boolean = false,
    val started: Boolean = false,
) : Serializable {
    init { if (revision < 0L) throw InvalidShiftCoverageInput("assignment revision must be non-negative") }
    companion object { private const val serialVersionUID: Long = 1L }
}

/** planner가 읽는 immutable canonical snapshot입니다. */
data class ShiftCoverageSnapshot(
    val siteId: SiteId,
    val workers: List<ShiftWorker>,
    val shifts: List<Shift>,
    val assignments: List<ShiftAssignment> = emptyList(),
    val planId: PlanId = PlanId.new(),
    val generationId: GenerationId = GenerationId.new(),
    val aggregateRevision: Long = 0L,
    val schemaVersion: String = "v1",
) : Serializable {
    init {
        if (workers.size > ShiftCoverageLimits.MAX_WORKERS) throw InvalidShiftCoverageInput("too many workers")
        if (shifts.size > ShiftCoverageLimits.MAX_SHIFTS) throw InvalidShiftCoverageInput("too many shifts")
        if (assignments.size > ShiftCoverageLimits.MAX_ASSIGNMENTS) throw InvalidShiftCoverageInput("too many assignments")
        if (aggregateRevision < 0L) throw InvalidShiftCoverageInput("aggregate revision must be non-negative")
    }
    companion object { private const val serialVersionUID: Long = 1L }
}

/** signed minor-unit score입니다. overflow는 도메인 오류로 변환합니다. */
data class CoverageScore(val coverageMinor: Long = 0L, val costMinor: Long = 0L, val fairnessMinor: Long = 0L) : Serializable {
    val totalMinor: Long
        get() = add(coverageMinor, costMinor, fairnessMinor)

    operator fun plus(other: CoverageScore): CoverageScore = CoverageScore(
        coverageMinor = safeAdd(coverageMinor, other.coverageMinor),
        costMinor = safeAdd(costMinor, other.costMinor),
        fairnessMinor = safeAdd(fairnessMinor, other.fairnessMinor),
    )

    companion object {
        private const val serialVersionUID: Long = 1L
        private fun safeAdd(first: Long, second: Long): Long = try {
            Math.addExact(first, second)
        } catch (failure: ArithmeticException) {
            throw ShiftCoverageArithmeticError("score addition overflow", failure)
        }
        private fun add(first: Long, second: Long, third: Long): Long = safeAdd(safeAdd(first, second), third)
    }
}

/** planner의 stable failure reason입니다. */
enum class ShiftCoverageReason { OVERLAP, UNAVAILABLE, MISSING_SKILL, REST_RULE, STARTED_SHIFT, PINNED, NO_CANDIDATE }

/** Issue #526이 수렴시켜야 하는 외부 event의 closed set입니다. */
enum class ShiftCoverageEventType(val wireName: String) {
    AVAILABILITY_CHANGED("availability.changed"),
    SHIFT_DEMAND_CHANGED("shift.demand_changed"),
    WORKER_SICK_CALLED("worker.sick_called"),
    SWAP_REQUESTED("swap.requested"),
    SWAP_ACCEPTED("swap.accepted"),
    SHIFT_STARTED("shift.started"),
}

/** route에 materialize된 assignment입니다. */
data class PlannedShift(val shiftId: ShiftId, val workerId: WorkerId, val assignmentId: AssignmentId, val pinned: Boolean) : Serializable

/** plan에 포함하는 명시적 gap입니다. */
data class UnassignedShift(val shiftId: ShiftId, val reason: ShiftCoverageReason) : Serializable

/** deterministic planner 결과입니다. */
data class ShiftCoveragePlanProposal(
    val planId: PlanId,
    val generationId: GenerationId,
    val revision: Long,
    val siteId: SiteId? = null,
    val assignments: List<PlannedShift>,
    val unassigned: List<UnassignedShift>,
    val score: CoverageScore,
    val candidateEvaluations: Int,
    val snapshotDigest: SnapshotDigest? = null,
) : Serializable

/** 사람이 확인하는 swap 요청입니다. */
data class ShiftSwapRequest(
    val requestId: SwapRequestId,
    val siteId: SiteId,
    val shiftId: ShiftId,
    val sourceWorkerId: WorkerId,
    val targetWorkerId: WorkerId,
    val expectedAssignmentRevision: Long,
    val expectedPlanRevision: Long,
    val idempotencyKey: IdempotencyKey,
) : Serializable {
    init {
        if (sourceWorkerId == targetWorkerId) throw InvalidShiftCoverageInput("swap workers must differ")
        if (expectedAssignmentRevision < 0L || expectedPlanRevision < 0L) throw InvalidShiftCoverageInput("expected revisions must be non-negative")
    }
}
