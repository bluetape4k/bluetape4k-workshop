package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.CoverageScore
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.IdempotencyKey
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.Shift
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlanProposal
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageSnapshot
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageConflict
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageConflictCode
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftSwapRequest
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftWorker
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.Skill
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.TimeInterval
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageRepository
import io.bluetape4k.workshop.optimization.shiftcoverage.planner.DeterministicShiftCoveragePlanner
import io.bluetape4k.workshop.optimization.shiftcoverage.planner.ShiftCoverageCanonicalizer
import io.bluetape4k.workshop.optimization.shiftcoverage.observability.ShiftCoverageObservations
import java.time.Clock
import java.time.Instant
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

data class ShiftCoverageSwapView(val requestId: String, val shiftId: String, val sourceWorkerId: String, val targetWorkerId: String, val status: String)

data class ShiftCoverageReplanResult(val proposal: ShiftCoveragePlanProposal, val requestId: String, val replay: Boolean)

/** demo profile의 synthetic fixture와 application services를 묶는 bounded facade입니다. */
@Profile("demo")
@Service
class ShiftCoverageDemoService(
    private val clock: Clock = Clock.systemUTC(),
    private val observations: ShiftCoverageObservations? = null,
) {
    private val planner = DeterministicShiftCoveragePlanner()
    private val canonicalizer = ShiftCoverageCanonicalizer()
    private val plans = ShiftCoveragePlanStore()
    private val generations = ShiftCoverageGenerationStore()
    private val assignments = ShiftCoverageRepository()
    private val approval = ShiftCoverageApprovalService(plans, assignments)
    private val swaps = ShiftCoverageSwapService(assignments)
    private val swapRequests = ConcurrentHashMap<String, ShiftSwapRequest>()
    private val idempotency = ShiftCoverageIdempotencyStore()
    private val revisions = AtomicLong(0L)
    private val siteId = SiteId("site-demo")

    fun listPlans(workerId: WorkerId?): List<ShiftCoveragePlanProposal> = buildList {
        val latest = plans.find(PlanId("plan-demo"), revisions.get())
        if (latest != null) add(latest.proposal)
    }.map { proposal ->
        if (workerId == null) proposal else proposal.copy(assignments = proposal.assignments.filter { it.workerId == workerId })
    }

    fun listSwaps(workerId: WorkerId?): List<ShiftCoverageSwapView> = swapRequests.values.asSequence()
        .filter { workerId == null || it.sourceWorkerId == workerId }
        .sortedBy { it.requestId.value }
        .map { ShiftCoverageSwapView(it.requestId.value, it.shiftId.value, it.sourceWorkerId.value, it.targetWorkerId.value, "REQUESTED") }
        .toList()

    fun replan(): ShiftCoveragePlanProposal {
        val revision = revisions.incrementAndGet()
        val snapshot = fixture(revision)
        val generation = generations.request(
            planId = snapshot.planId,
            aggregateRevision = snapshot.aggregateRevision,
            snapshotDigest = canonicalizer.digest(snapshot),
            generationId = snapshot.generationId,
            requestedAt = clock.instant(),
        )
        generations.start(generation.generationId)
        return try {
            val proposal = observations?.observePlan { planner.plan(snapshot) } ?: planner.plan(snapshot)
            plans.save(StoredShiftCoveragePlan(proposal))
            generations.succeed(generation.generationId, clock.instant())
            observations?.recordReplan("accepted")
            proposal
        } catch (failure: Throwable) {
            generations.fail(generation.generationId, failure.message ?: failure::class.simpleName.orEmpty(), clock.instant())
            observations?.recordReplan("rejected")
            throw failure
        }
    }

    fun generation(generationId: GenerationId): ShiftCoverageGenerationRecord? = generations.find(generationId)

    fun replan(idempotencyKey: IdempotencyKey, principal: String, requestId: String): ShiftCoverageReplanResult {
        require(requestId.isNotBlank()) { "request id is required" }
        val namespace = IdempotencyNamespace("POST", "/api/shift-coverage/replans", siteId.value, principal, idempotencyKey)
        val claim = idempotency.begin(namespace, fingerprint("replan", principal))
        return when (claim.kind) {
            IdempotencyClaimKind.NEW -> {
                val proposal = replan()
                idempotency.complete(namespace, "${proposal.revision}|$requestId")
                ShiftCoverageReplanResult(proposal, requestId, replay = false)
            }
            IdempotencyClaimKind.REPLAY -> replayReplan(claim.response)
            IdempotencyClaimKind.REUSED -> throw ShiftCoverageConflict(ShiftCoverageConflictCode.IDEMPOTENCY_KEY_REUSED)
            IdempotencyClaimKind.IN_PROGRESS -> throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
        }
    }

    fun approve(revision: Long): Boolean = approval.approve(PlanId("plan-demo"), revision)

    fun approve(revision: Long, idempotencyKey: IdempotencyKey, principal: String): Boolean =
        idempotentBoolean(
            IdempotencyNamespace("POST", "/api/shift-coverage/plans/$revision/approve", siteId.value, principal, idempotencyKey),
            fingerprint("approve", revision.toString()),
        ) { approval.approve(PlanId("plan-demo"), revision).also { observations?.recordApproval(if (it) "accepted" else "conflict") } }

    fun requestSwap(sourceWorkerId: WorkerId, targetWorkerId: WorkerId, idempotencyKey: IdempotencyKey): ShiftSwapRequest {
        val assignment = assignments.listAssignments().firstOrNull { it.workerId == sourceWorkerId }
            ?: ShiftAssignment(AssignmentId.new(), siteId, ShiftId("shift-demo"), sourceWorkerId)
        assignments.saveAssignment(assignment)
        val request = ShiftSwapRequest(
            requestId = io.bluetape4k.workshop.optimization.shiftcoverage.domain.SwapRequestId.new(),
            siteId = siteId,
            shiftId = assignment.shiftId,
            sourceWorkerId = sourceWorkerId,
            targetWorkerId = targetWorkerId,
            expectedAssignmentRevision = assignment.revision,
            expectedPlanRevision = revisions.get(),
            idempotencyKey = idempotencyKey,
        )
        swapRequests[request.requestId.value] = request
        return request
    }

    fun requestSwap(sourceWorkerId: WorkerId, targetWorkerId: WorkerId, idempotencyKey: IdempotencyKey, principal: String): ShiftSwapRequest =
        idempotentSwap(
            IdempotencyNamespace("POST", "/api/shift-coverage/swaps", siteId.value, principal, idempotencyKey),
            fingerprint("swap", sourceWorkerId.value, targetWorkerId.value),
        ) { requestSwap(sourceWorkerId, targetWorkerId, idempotencyKey) }

    fun acceptSwap(requestId: String): Boolean {
        val request = swapRequests[requestId] ?: return false
        return swaps.accept(
            ShiftSwapAcceptance(
                assignmentId = assignments.findByShift(request.shiftId)?.assignmentId ?: return false,
                targetWorkerId = request.targetWorkerId,
                expectedRevision = request.expectedAssignmentRevision,
                expectedPlanRevision = request.expectedPlanRevision,
                idempotencyKey = request.idempotencyKey,
            ),
        )
    }

    fun acceptSwap(requestId: String, idempotencyKey: IdempotencyKey, principal: String): Boolean =
        idempotentBoolean(
            IdempotencyNamespace("POST", "/api/shift-coverage/swaps/$requestId/accept", siteId.value, principal, idempotencyKey),
            fingerprint("accept", requestId),
        ) { acceptSwap(requestId).also { observations?.recordSwap(if (it) "accepted" else "conflict") } }

    fun assignmentCount(): Int = assignments.listAssignments().size

    private fun fixture(revision: Long): ShiftCoverageSnapshot {
        val start = Instant.parse("2026-08-24T09:00:00Z")
        val end = Instant.parse("2026-08-24T17:00:00Z")
        val skill = Skill("electrical")
        val workers = listOf(
            ShiftWorker(WorkerId("worker-a"), siteId, "Worker A", setOf(skill), listOf(TimeInterval(start, end))),
            ShiftWorker(WorkerId("worker-b"), siteId, "Worker B", setOf(skill), listOf(TimeInterval(start, end))),
        )
        val shift = Shift(ShiftId("shift-demo"), siteId, start, end, setOf(skill))
        return ShiftCoverageSnapshot(siteId, workers, listOf(shift), planId = PlanId("plan-demo"), generationId = GenerationId("generation-$revision"), aggregateRevision = revision)
    }

    private fun replayReplan(response: String?): ShiftCoverageReplanResult {
        val parts = response?.split('|', limit = 2) ?: throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
        val revision = parts.firstOrNull()?.toLongOrNull() ?: throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
        val requestId = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
        val proposal = plans.find(PlanId("plan-demo"), revision)?.proposal
            ?: throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
        return ShiftCoverageReplanResult(proposal, requestId, replay = true)
    }

    private fun idempotentBoolean(namespace: IdempotencyNamespace, fingerprint: String, operation: () -> Boolean): Boolean {
        val claim = idempotency.begin(namespace, fingerprint)
        return when (claim.kind) {
            IdempotencyClaimKind.NEW -> operation().also { idempotency.complete(namespace, it.toString()) }
            IdempotencyClaimKind.REPLAY -> claim.response?.toBooleanStrictOrNull()
                ?: throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
            IdempotencyClaimKind.REUSED -> throw ShiftCoverageConflict(ShiftCoverageConflictCode.IDEMPOTENCY_KEY_REUSED)
            IdempotencyClaimKind.IN_PROGRESS -> throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
        }
    }

    private fun idempotentSwap(namespace: IdempotencyNamespace, fingerprint: String, operation: () -> ShiftSwapRequest): ShiftSwapRequest {
        val claim = idempotency.begin(namespace, fingerprint)
        return when (claim.kind) {
            IdempotencyClaimKind.NEW -> operation().also { idempotency.complete(namespace, it.requestId.value) }
            IdempotencyClaimKind.REPLAY -> swapRequests[claim.response]
                ?: throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
            IdempotencyClaimKind.REUSED -> throw ShiftCoverageConflict(ShiftCoverageConflictCode.IDEMPOTENCY_KEY_REUSED)
            IdempotencyClaimKind.IN_PROGRESS -> throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
        }
    }

    private fun fingerprint(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray(UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

}
