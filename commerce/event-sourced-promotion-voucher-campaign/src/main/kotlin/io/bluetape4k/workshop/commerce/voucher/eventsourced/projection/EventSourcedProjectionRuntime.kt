package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedRuntimeWorkers
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val VOUCHER_LIFECYCLE_PROJECTION = "voucher-lifecycle"
private const val RUNTIME_POLL_INTERVAL_MILLIS = 100L

internal data class ProjectionRuntimeResources(
    val database: Database,
    val permits: EventSourcedDatabasePermitGate,
    val leases: ProjectionLeaseRepository,
    val projections: ProjectionRepository,
    val rebuilds: ProjectionRebuildRepository,
)

/**
 * Spring-owned runtime for the active projector and one durable rebuild candidate.
 *
 * Every poll enters its reserved database lane before requesting a Hikari connection. Candidate
 * activation occurs only after both generations have the target checkpoint and equal semantic
 * digests. Cancellation is recovered durably even after a process restart.
 */
internal class EventSourcedProjectionRuntime(
    resources: ProjectionRuntimeResources,
    private val properties: ProjectionWorkerProperties,
    private val clock: Clock,
    private val digest: ProjectionGenerationDigest = ProjectionGenerationDigest(),
    private val maintenance: ProjectionRebuildMaintenance = ProjectionRebuildMaintenance(),
) : EventSourcedRuntimeWorkers {
    private val projections = resources.projections
    private val rebuilds = resources.rebuilds
    private val projectionTransactions =
        EventSourcedPermitTransactionRunner(
            resources.database,
            resources.permits,
            EventSourcedDatabaseLane.PROJECTION,
        )
    private val rebuildTransactions =
        EventSourcedPermitTransactionRunner(
            resources.database,
            resources.permits,
            EventSourcedDatabaseLane.REBUILD,
        )
    private val maintenanceTransactions =
        EventSourcedPermitTransactionRunner(
            resources.database,
            resources.permits,
            EventSourcedDatabaseLane.MAINTENANCE,
        )
    private val projectionWorker =
        ProjectionWorker(projections, ExposedProjectionEventReader())
    private val rebuildWorker =
        ProjectionRebuildWorker(rebuilds, projections, ExposedProjectionEventReader())
    private val runtimeLeases =
        ProjectionRuntimeLeaseCoordinator(
            database = resources.database,
            leases = resources.leases,
            ownerDigest = ReceiptDigest.sha256("voucher-projection-runtime\u0000${UUID.randomUUID()}").value,
            clock = clock,
        )
    private val started = AtomicBoolean()
    private val projectionEnabled = AtomicBoolean()
    private val rebuildEnabled = AtomicBoolean()

    @Volatile
    private var executor: ScheduledExecutorService? = null

    @Volatile
    private var scheduled: ScheduledFuture<*>? = null

    override fun start() {
        if (!properties.enabled || !started.compareAndSet(false, true)) return
        maintenanceTransactions.inTransaction {
            rebuilds.initializeActive(VOUCHER_LIFECYCLE_PROJECTION, clock.instant())
        }
        projectionEnabled.set(true)
        rebuildEnabled.set(true)
        val runtimeExecutor =
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("voucher-projection-runtime-", 0).factory(),
            )
        executor = runtimeExecutor
        scheduled =
            runtimeExecutor.scheduleWithFixedDelay(
                ::tickSafely,
                0,
                RUNTIME_POLL_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        log.info { "voucher_projection_runtime_started" }
    }

    override fun stopProjection() {
        projectionEnabled.set(false)
    }

    override fun stopRebuild() {
        rebuildEnabled.set(false)
    }

    override fun stopMaintenance() {
        scheduled?.cancel(false)
        executor?.shutdown()
    }

    override fun releaseFencedLeases() {
        runtimeLeases.releaseAll()
    }

    private fun tickSafely() {
        runCatching {
            if (projectionEnabled.get()) pollActive()
            if (rebuildEnabled.get()) pollRebuild()
        }.onFailure { failure ->
            log.warn { "voucher_projection_runtime_tick_failed cause=${failure.javaClass.simpleName}" }
        }
    }

    private fun pollActive() {
        projectionTransactions.inTransaction {
            val now = clock.instant()
            val pointer = findActive(VOUCHER_LIFECYCLE_PROJECTION) ?: return@inTransaction
            val key = ProjectionKey(pointer.projection, pointer.generation)
            val owned = runtimeLeases.acquireActive(key, now)
            owned?.let { projectionWorker.poll(key, it.lease, now) }
        }
    }

    private fun pollRebuild() {
        rebuildTransactions.inTransaction {
            val now = clock.instant()
            when (val generation = findInProgressGeneration(VOUCHER_LIFECYCLE_PROJECTION)) {
                null -> runtimeLeases.releaseRebuild(now)
                else ->
                    when (generation.state) {
                        ProjectionGenerationState.BUILDING -> pollBuilding(generation, now)
                        ProjectionGenerationState.VALIDATING -> validateAndActivate(generation, now)
                        ProjectionGenerationState.CANCELLING -> {
                            maintenance.recover(generation.key, now)
                            runtimeLeases.releaseRebuild(now)
                        }
                        else -> Unit
                    }
            }
        }
    }

    private fun pollBuilding(
        generation: ProjectionGeneration,
        now: Instant,
    ) {
        val owned = runtimeLeases.acquireRebuild(generation.key, now)
        owned?.let { rebuildWorker.poll(generation.key, it.lease, now) }
        val current = findGeneration(generation.key) ?: return
        if (current.state == ProjectionGenerationState.BUILDING &&
            current.currentPosition == current.targetPosition
        ) {
            rebuilds.beginValidation(
                key = current.key,
                fencingToken = current.fencingToken,
                cancellationRevision = current.cancellationRevision,
                canonicalDigest = digest.compute(current.key),
                now = now,
            )
        }
    }

    private fun validateAndActivate(
        candidate: ProjectionGeneration,
        now: Instant,
    ) {
        val pointer = findActive(candidate.key.projection) ?: return
        val activeKey = ProjectionKey(pointer.projection, pointer.generation)
        val activePosition = projections.checkpoint(activeKey)?.position ?: 0L
        val candidatePosition = projections.checkpoint(candidate.key)?.position ?: 0L
        val checkpointsMatch =
            activePosition == candidate.targetPosition && candidatePosition == candidate.targetPosition
        when {
            activePosition > candidate.targetPosition ->
                rebuilds.extendValidationTarget(
                    key = candidate.key,
                    extension =
                        ProjectionValidationTargetExtension(
                            fencingToken = candidate.fencingToken,
                            cancellationRevision = candidate.cancellationRevision,
                            currentTargetPosition = candidate.targetPosition,
                            newTargetPosition = activePosition,
                        ),
                    now = now,
                )

            checkpointsMatch -> {
                val candidateDigest = checkNotNull(candidate.canonicalDigest)
                if (digest.compute(activeKey) == candidateDigest) {
                    if (rebuilds.activateCandidate(pointer, candidate, candidateDigest, now)) {
                        runtimeLeases.releaseRebuild(now)
                        log.info {
                            "voucher_projection_rebuild_activated generation=${candidate.key.generation}"
                        }
                    }
                } else {
                    log.warn {
                        "voucher_projection_validation_mismatch generation=${candidate.key.generation}"
                    }
                }
            }
        }
    }

    private companion object : KLogging()
}

private data class OwnedProjectionLease(
    val key: ProjectionKey,
    val lease: ProjectionLease,
)

private fun ProjectionRebuildRepository.activateCandidate(
    pointer: ActiveProjectionGeneration,
    candidate: ProjectionGeneration,
    candidateDigest: ReceiptDigest,
    now: Instant,
): Boolean =
    activate(
        key = candidate.key,
        expectedPointerRevision = pointer.revision,
        targetHead = candidate.targetPosition,
        canonicalDigest = candidateDigest,
        now = now,
    ) == ProjectionActivationResult.Activated

private class ProjectionRuntimeLeaseCoordinator(
    private val database: Database,
    private val leases: ProjectionLeaseRepository,
    private val ownerDigest: String,
    private val clock: Clock,
) {
    private var active: OwnedProjectionLease? = null
    private var rebuild: OwnedProjectionLease? = null

    fun acquireActive(
        key: ProjectionKey,
        now: Instant,
    ): OwnedProjectionLease? = refresh(active, key, now).also { active = it }

    fun acquireRebuild(
        key: ProjectionKey,
        now: Instant,
    ): OwnedProjectionLease? = refresh(rebuild, key, now).also { rebuild = it }

    fun releaseRebuild(now: Instant) {
        rebuild?.release(now)
        rebuild = null
    }

    fun releaseAll() {
        val observedActive = active
        val observedRebuild = rebuild
        transaction(database) {
            observedActive?.release(clock.instant())
            observedRebuild?.takeUnless { it.key == observedActive?.key }?.release(clock.instant())
        }
        active = null
        rebuild = null
    }

    private fun refresh(
        current: OwnedProjectionLease?,
        key: ProjectionKey,
        now: Instant,
    ): OwnedProjectionLease? {
        current?.takeIf { it.key != key }?.release(now)
        val matching = current?.takeIf { it.key == key }
        val lease =
            matching
                ?.let { owned ->
                    if (owned.lease.isRenewalDue(now)) {
                        leases.renewLease(key.projection, key.generation, owned.lease, now)
                    } else {
                        owned.lease
                    }
                }
                ?: leases.acquire(key.projection, key.generation, ownerDigest, now)
        return lease?.let { OwnedProjectionLease(key, it) }
    }

    private fun OwnedProjectionLease.release(now: Instant) {
        leases.release(key.projection, key.generation, lease, now)
    }
}
