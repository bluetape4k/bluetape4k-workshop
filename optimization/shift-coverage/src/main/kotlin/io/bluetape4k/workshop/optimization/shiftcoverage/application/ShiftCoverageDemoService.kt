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
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageLimits
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlannerFailure
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlannerFailureCode
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftSwapRequest
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftWorker
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.Skill
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.TimeInterval
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageRepository
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageAssignmentStore
import io.bluetape4k.workshop.optimization.shiftcoverage.planner.DeterministicShiftCoveragePlanner
import io.bluetape4k.workshop.optimization.shiftcoverage.planner.ShiftCoverageCanonicalizer
import io.bluetape4k.workshop.optimization.shiftcoverage.observability.ShiftCoverageObservations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.springframework.stereotype.Service

data class ShiftCoverageSwapView(val requestId: String, val shiftId: String, val sourceWorkerId: String, val targetWorkerId: String, val status: String)

data class ShiftCoverageReplanResult(val proposal: ShiftCoveragePlanProposal, val requestId: String, val replay: Boolean)

/** demo와 postgres profile의 synthetic fixture/application services를 묶는 bounded facade입니다. */
@Service
class ShiftCoverageDemoService(
    private val clock: Clock = Clock.systemUTC(),
    private val observations: ShiftCoverageObservations? = null,
    assignmentStore: ShiftCoverageAssignmentStore? = null,
    idempotencyStore: ShiftCoverageIdempotencyPort? = null,
    private val plannerLifecycle: ShiftCoverageExecutorLifecycle? = null,
    private val plannerTimeout: Duration = Duration.ofMillis(ShiftCoverageLimits.MAX_PLANNER_MILLIS),
) {
    private val planner = DeterministicShiftCoveragePlanner()
    private val canonicalizer = ShiftCoverageCanonicalizer()
    private val plans = ShiftCoveragePlanStore()
    private val generations = ShiftCoverageGenerationStore()
    private val assignments: ShiftCoverageAssignmentStore = assignmentStore ?: ShiftCoverageRepository()
    private val approval = ShiftCoverageApprovalService(plans, assignments)
    private val revisions = AtomicLong(0L)
    private val siteId = SiteId("site-demo")
    private val swaps = ShiftCoverageSwapService(
        repository = assignments,
        currentPlanRevision = { revisions.get() },
        targetAllowed = { target -> target.targetWorkerId in DEMO_WORKERS && target.current.siteId == siteId },
    )
    private val swapRequests = ConcurrentHashMap<String, ShiftSwapRequest>()
    private val idempotency: ShiftCoverageIdempotencyPort = idempotencyStore ?: ShiftCoverageIdempotencyStore()

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
            val proposal = plan(snapshot)
            plans.save(StoredShiftCoveragePlan(proposal))
            generations.succeed(generation.generationId, clock.instant())
            observations?.recordReplan("accepted")
            proposal
        } catch (cancelled: CancellationException) {
            generations.cancel(generation.generationId, clock.instant())
            observations?.recordReplan("cancelled")
            throw cancelled
        } catch (failure: Exception) {
            generations.fail(generation.generationId, failure.message ?: failure::class.simpleName.orEmpty(), clock.instant())
            observations?.recordReplan("rejected")
            throw failure
        }
    }

    /** bounded CPU admission을 통과한 planner만 proposal을 생성하도록 합니다. */
    private fun plan(snapshot: ShiftCoverageSnapshot): ShiftCoveragePlanProposal {
        val lifecycle = plannerLifecycle
        if (lifecycle == null) {
            return observations?.observePlan { planner.plan(snapshot) } ?: planner.plan(snapshot)
        }
        val task = lifecycle.submitCallable(
            Callable { observations?.observePlan { planner.plan(snapshot) } ?: planner.plan(snapshot) },
        ) ?: throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED, "planner admission is full")
        return try {
            task.get(plannerTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            task.cancel(true)
            throw ShiftCoveragePlannerFailure(PlannerFailureCode.REPLAN_TIMEOUT, "planner deadline exceeded")
        } catch (interrupted: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw ShiftCoveragePlannerFailure(PlannerFailureCode.REPLAN_TIMEOUT, "planner interrupted")
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            when (cause) {
                is RuntimeException -> throw cause
                else -> throw IllegalStateException("planner execution failed", cause)
            }
        }
    }

    fun generation(generationId: GenerationId): ShiftCoverageGenerationRecord? = generations.find(generationId)

    fun replan(idempotencyKey: IdempotencyKey, principal: String, requestId: String): ShiftCoverageReplanResult {
        require(requestId.isNotBlank()) { "request id is required" }
        val namespace = IdempotencyNamespace("POST", "/api/shift-coverage/replans", siteId.value, principal, idempotencyKey)
        val claim = idempotency.begin(namespace, fingerprint("replan", principal))
        return when (claim.kind) {
            IdempotencyClaimKind.NEW -> {
                try {
                    val proposal = replan()
                    idempotency.complete(namespace, "${proposal.revision}|$requestId")
                    ShiftCoverageReplanResult(proposal, requestId, replay = false)
                } catch (failure: Exception) {
                    idempotency.abort(namespace)
                    throw failure
                }
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
        if (sourceWorkerId == targetWorkerId || sourceWorkerId !in DEMO_WORKERS || targetWorkerId !in DEMO_WORKERS) {
            throw InvalidShiftCoverageInput("swap workers are outside the demo scope")
        }
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
            IdempotencyClaimKind.NEW -> try {
                operation().also { idempotency.complete(namespace, it.toString()) }
            } catch (failure: Exception) {
                idempotency.abort(namespace)
                throw failure
            }
            IdempotencyClaimKind.REPLAY -> claim.response?.toBooleanStrictOrNull()
                ?: throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
            IdempotencyClaimKind.REUSED -> throw ShiftCoverageConflict(ShiftCoverageConflictCode.IDEMPOTENCY_KEY_REUSED)
            IdempotencyClaimKind.IN_PROGRESS -> throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
        }
    }

    private fun idempotentSwap(namespace: IdempotencyNamespace, fingerprint: String, operation: () -> ShiftSwapRequest): ShiftSwapRequest {
        val claim = idempotency.begin(namespace, fingerprint)
        return when (claim.kind) {
            IdempotencyClaimKind.NEW -> try {
                operation().also { idempotency.complete(namespace, it.requestId.value) }
            } catch (failure: Exception) {
                idempotency.abort(namespace)
                throw failure
            }
            IdempotencyClaimKind.REPLAY -> swapRequests[claim.response]
                ?: throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
            IdempotencyClaimKind.REUSED -> throw ShiftCoverageConflict(ShiftCoverageConflictCode.IDEMPOTENCY_KEY_REUSED)
            IdempotencyClaimKind.IN_PROGRESS -> throw ShiftCoverageConflict(ShiftCoverageConflictCode.REPLAN_REJECTED)
        }
    }

    private fun fingerprint(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray(UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private val DEMO_WORKERS = setOf(WorkerId("worker-a"), WorkerId("worker-b"))
    }

}
