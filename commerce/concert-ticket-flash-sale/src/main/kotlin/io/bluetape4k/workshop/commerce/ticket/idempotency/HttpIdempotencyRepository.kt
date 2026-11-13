package io.bluetape4k.workshop.commerce.ticket.idempotency

import io.bluetape4k.javatimes.hours
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketHttpIdempotencies
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketHttpIdempotencyEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketLockRank
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.data.repository.findByIdOrNull
import java.io.Serial
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.*

/** owner scope가 완전하게 포함된 HTTP idempotency key입니다. */
data class IdempotencyScope(
    val principalSubjectId: UUID,
    val httpMethod: String,
    val canonicalRoute: String,
    val resourceId: String,
    val operation: String,
    val keyDigest: TicketDigest,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** sale state나 Redis admission보다 먼저 평가되는 결과입니다. */
sealed interface IdempotencyDecision: Serializable {
    data class Owner(val id: Long): IdempotencyDecision {
        companion object {
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }

    data class Replay(val attemptId: UUID, val completed: Boolean): IdempotencyDecision {
        companion object {
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }

    data object InProgress: IdempotencyDecision {
        private fun readResolve(): Any = InProgress

        @Serial
        private const val serialVersionUID: Long = 1L
    }

    data object Conflict: IdempotencyDecision {
        private fun readResolve(): Any = Conflict

        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** principal scope replay와 fingerprint conflict를 담당하는 PostgreSQL authority입니다. */
class HttpIdempotencyRepository(
    private val jdbc: TicketJdbcExecutor,
    private val retention: Duration = 24.hours(),
): TicketExposedJdbcRepository<TicketHttpIdempotencyEntity, Long>(TicketHttpIdempotencyEntity::class) {

    fun acquire(
        scope: IdempotencyScope,
        fingerprint: TicketDigest,
        now: Instant,
    ): IdempotencyDecision =
        try {
            jdbc.transaction {
                acquire(TicketLockRank.IDEMPOTENCY)
                val id = TicketHttpIdempotencies.insertAndGetId {
                    it[principalSubjectId] = scope.principalSubjectId
                    it[httpMethod] = scope.httpMethod
                    it[canonicalRoute] = scope.canonicalRoute
                    it[resourceId] = scope.resourceId
                    it[operation] = scope.operation
                    it[idempotencyKeyDigest] = scope.keyDigest.bytes()
                    it[requestFingerprint] = fingerprint.bytes()
                    it[status] = "in_progress"
                    it[expiresAt] = now + retention
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value
                IdempotencyDecision.Owner(id)
            }
        } catch (failure: ExposedSQLException) {
            if (failure.sqlState != UNIQUE_VIOLATION) throw failure
            existing(scope, fingerprint)
        }

    fun attachAttempt(id: Long, attemptId: UUID) {
        jdbc.transaction {
            acquire(TicketLockRank.IDEMPOTENCY)
            val entity = findByIdOrNull(id) ?: error("idempotency row not found")
            entity.attemptId = attemptId
            entity.updatedAt = Instant.now()
            save(entity)
        }
    }

    private fun existing(
        scope: IdempotencyScope,
        fingerprint: TicketDigest,
    ): IdempotencyDecision =
        jdbc.transaction {
            acquire(TicketLockRank.IDEMPOTENCY)
            val entity = TicketHttpIdempotencies.selectAll()
                .where {
                    (TicketHttpIdempotencies.principalSubjectId eq scope.principalSubjectId) and
                            (TicketHttpIdempotencies.httpMethod eq scope.httpMethod) and
                            (TicketHttpIdempotencies.canonicalRoute eq scope.canonicalRoute) and
                            (TicketHttpIdempotencies.resourceId eq scope.resourceId) and
                            (TicketHttpIdempotencies.operation eq scope.operation) and
                            (TicketHttpIdempotencies.idempotencyKeyDigest eq scope.keyDigest.bytes())
                }
                .forUpdate()
                .singleOrNull()
                ?.let { findByIdOrNull(it[TicketHttpIdempotencies.id].value) }
                ?: error("idempotency winner disappeared")

            if (!entity.requestFingerprint.contentEquals(fingerprint.bytes())) {
                IdempotencyDecision.Conflict
            } else {
                entity.attemptId
                    ?.let { IdempotencyDecision.Replay(it, entity.status == "completed") }
                    ?: IdempotencyDecision.InProgress
            }
        }

    companion object {
        private const val UNIQUE_VIOLATION = "23505"
    }
}
