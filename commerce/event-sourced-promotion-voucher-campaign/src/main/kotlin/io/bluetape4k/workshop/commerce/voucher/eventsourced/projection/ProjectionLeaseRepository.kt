package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant

internal const val PROJECTION_LEASE_TTL_SECONDS = 15L
internal const val PROJECTION_LEASE_RENEW_SECONDS = 5L
private val DEFAULT_PROJECTION_LEASE: Duration = Duration.ofSeconds(PROJECTION_LEASE_TTL_SECONDS)

/**
 * Fenced ownership for one projection generation. A newer owner must always hold a larger token.
 */
internal data class ProjectionLease(
    val ownerDigest: String,
    val fencingToken: Long,
    val leaseDeadline: Instant,
) {
    init {
        ownerDigest.requireNotBlank("ownerDigest")
        fencingToken.requirePositiveNumber("fencingToken")
    }
}

/**
 * PostgreSQL lease authority. Callers must perform projection writes with the returned fencing token.
 */
internal class ProjectionLeaseRepository {

    fun acquire(
        projection: String,
        generation: Long,
        ownerDigest: String,
        now: Instant,
        lease: Duration = DEFAULT_PROJECTION_LEASE,
    ): ProjectionLease? {
        TransactionManager.current()
        validateLeaseRequest(projection, generation, ownerDigest, lease)
        val leaseDeadline = now.plus(lease)
        val inserted =
            ProjectionLeases.insertIgnore { row ->
                row[ProjectionLeases.projection] = projection
                row[ProjectionLeases.generation] = generation
                row[ProjectionLeases.ownerDigest] = ownerDigest
                row[ProjectionLeases.leaseDeadline] = leaseDeadline
                row[ProjectionLeases.fencingToken] = FIRST_FENCING_TOKEN
                row[ProjectionLeases.updatedAt] = now
            }.insertedCount == 1
        if (inserted) {
            log.debug { "voucher_projection_lease_acquired projection=$projection generation=$generation" }
            return ProjectionLease(ownerDigest, FIRST_FENCING_TOKEN, leaseDeadline)
        }
        return acquireExisting(projection, generation, ownerDigest, now, leaseDeadline)
    }

    fun renew(
        projection: String,
        generation: Long,
        lease: ProjectionLease,
        now: Instant,
        duration: Duration = DEFAULT_PROJECTION_LEASE,
    ): Boolean = renewLease(projection, generation, lease, now, duration) != null

    fun renewLease(
        projection: String,
        generation: Long,
        lease: ProjectionLease,
        now: Instant,
        duration: Duration = DEFAULT_PROJECTION_LEASE,
    ): ProjectionLease? {
        TransactionManager.current()
        validateLeaseRequest(projection, generation, lease.ownerDigest, duration)
        val nextDeadline = now.plus(duration)
        val renewed =
            ProjectionLeases.update(
                where = {
                    leasePredicate(projection, generation, lease) and
                        (ProjectionLeases.leaseDeadline greaterEq now)
                },
            ) { row ->
                row[ProjectionLeases.leaseDeadline] = nextDeadline
                row[ProjectionLeases.updatedAt] = now
            } == 1
        log.debug { "voucher_projection_lease_renewed projection=$projection generation=$generation renewed=$renewed" }
        return if (renewed) lease.copy(leaseDeadline = nextDeadline) else null
    }

    fun release(
        projection: String,
        generation: Long,
        lease: ProjectionLease,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        val released =
            ProjectionLeases.update(
                where = { leasePredicate(projection, generation, lease) },
            ) { row ->
                row[ProjectionLeases.leaseDeadline] = now
                row[ProjectionLeases.updatedAt] = now
            } == 1
        log.debug {
            "voucher_projection_lease_released projection=$projection generation=$generation released=$released"
        }
        return released
    }

    /** Holds the lease row lock until the caller's transaction commits. */
    fun requireActive(
        projection: String,
        generation: Long,
        lease: ProjectionLease,
        now: Instant,
    ) {
        TransactionManager.current()
        val active =
            ProjectionLeases
                .selectAll()
                .where {
                    leasePredicate(projection, generation, lease) and
                        (ProjectionLeases.leaseDeadline greaterEq now)
                }.forUpdate()
                .singleOrNull() != null
        if (!active) {
            throw ProjectionLeaseLostException(projection, generation)
        }
    }

    private fun acquireExisting(
        projection: String,
        generation: Long,
        ownerDigest: String,
        now: Instant,
        leaseDeadline: Instant,
    ): ProjectionLease? =
        find(projection, generation)
            ?.takeUnless { current -> current.leaseDeadline.isAfter(now) }
            ?.let { current ->
                val nextToken = current.fencingToken + 1
                val acquired =
                    ProjectionLeases.update(
                        where = {
                            leasePredicate(projection, generation, current)
                        },
                    ) { row ->
                        row[ProjectionLeases.ownerDigest] = ownerDigest
                        row[ProjectionLeases.leaseDeadline] = leaseDeadline
                        row[ProjectionLeases.fencingToken] = nextToken
                        row[ProjectionLeases.updatedAt] = now
                    } == 1
                if (!acquired) {
                    log.warn {
                        "voucher_projection_lease_takeover_lost projection=$projection generation=$generation"
                    }
                    null
                } else {
                    log.warn {
                        "voucher_projection_lease_taken_over projection=$projection generation=$generation"
                    }
                    ProjectionLease(ownerDigest, nextToken, leaseDeadline)
                }
            }

    companion object : KLogging()
}

internal class ProjectionLeaseLostException(
    projection: String,
    generation: Long,
) : IllegalStateException("projection lease is no longer active: $projection/$generation")

private const val FIRST_FENCING_TOKEN = 1L

private fun find(
    projection: String,
    generation: Long,
): ProjectionLease? =
    ProjectionLeases
        .selectAll()
        .where { (ProjectionLeases.projection eq projection) and (ProjectionLeases.generation eq generation) }
        .singleOrNull()
        ?.let(::toProjectionLease)

private fun toProjectionLease(row: ResultRow): ProjectionLease =
    ProjectionLease(
        ownerDigest = row[ProjectionLeases.ownerDigest],
        fencingToken = row[ProjectionLeases.fencingToken],
        leaseDeadline = row[ProjectionLeases.leaseDeadline],
    )

private fun leasePredicate(
    projection: String,
    generation: Long,
    lease: ProjectionLease,
) =
    (ProjectionLeases.projection eq projection) and
        (ProjectionLeases.generation eq generation) and
        (ProjectionLeases.ownerDigest eq lease.ownerDigest) and
        (ProjectionLeases.fencingToken eq lease.fencingToken) and
        (ProjectionLeases.leaseDeadline eq lease.leaseDeadline)

private fun validateLeaseRequest(
    projection: String,
    generation: Long,
    ownerDigest: String,
    lease: Duration,
) {
    projection.requireNotBlank("projection")
    generation.requirePositiveNumber("generation")
    ownerDigest.requireNotBlank("ownerDigest")
    lease.requireGt(Duration.ZERO, "lease")
}

internal fun ProjectionLease.isRenewalDue(now: Instant): Boolean {
    val renewalDueAt = leaseDeadline.minusSeconds(PROJECTION_LEASE_TTL_SECONDS - PROJECTION_LEASE_RENEW_SECONDS)
    return !now.isBefore(renewalDueAt)
}
