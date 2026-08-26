package io.bluetape4k.workshop.optimization.shiftcoverage.adapter

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.DatasetId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlanProposal
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageSnapshot
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SnapshotDigest
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId

/** provider 선택은 normalized ABI 뒤에만 존재하며 raw row/credential을 노출하지 않습니다. */
enum class ShiftCoverageProvider { FAKE, TIMEFOLD_PLATFORM, CUSTOM_SOLVER }

/** planner submit에 필요한 닫힌 request입니다. */
data class ShiftCoveragePlanningRequest(
    val provider: ShiftCoverageProvider,
    val datasetId: DatasetId,
    val generationId: GenerationId,
    val aggregateId: PlanId,
    val siteId: SiteId,
    val expectedRevision: Long,
    val canonicalizationVersion: String,
    val snapshotDigest: SnapshotDigest,
    val snapshot: ShiftCoverageSnapshot,
) {
    init { require(expectedRevision >= 0L) { "expectedRevision must be non-negative" } }
}

/** provider callback에 필요한 닫힌 proposal metadata입니다. */
data class ShiftCoveragePlanningCallback(
    val provider: ShiftCoverageProvider,
    val eventId: String,
    val requestId: String,
    val datasetId: DatasetId,
    val generationId: GenerationId,
    val aggregateId: PlanId,
    val siteId: SiteId,
    val targetAssignmentId: String?,
    val providerRevision: Long,
    val status: ShiftCoverageCallbackStatus,
    val proposalDigest: SnapshotDigest?,
    val score: Long,
    val reason: String?,
) {
    init { require(providerRevision >= 0L) { "providerRevision must be non-negative" } }
}

enum class ShiftCoverageCallbackStatus { SUCCEEDED, FAILED, REJECTED }

data class ShiftCoverageSubmission(
    val providerRequestId: String,
    val proposal: ShiftCoveragePlanProposal?,
)

data class ShiftCoverageAcceptance(val accepted: Boolean, val reason: String? = null)

/** submit/accept 두 method만 노출하는 provider-independent planning ABI입니다. */
interface ShiftCoveragePlanningPort {
    fun submit(request: ShiftCoveragePlanningRequest): ShiftCoverageSubmission
    fun accept(callback: ShiftCoveragePlanningCallback): ShiftCoverageAcceptance
}
