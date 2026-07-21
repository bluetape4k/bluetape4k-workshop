package io.bluetape4k.workshop.commerce.ticket.idempotency

import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketLockRank
import java.io.Serializable
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Complete owner-scoped HTTP idempotency key. */
data class IdempotencyScope(
    val principalSubjectId: UUID,
    val httpMethod: String,
    val canonicalRoute: String,
    val resourceId: String,
    val operation: String,
    val keyDigest: TicketDigest,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Result evaluated before sale state or Redis admission. */
sealed interface IdempotencyDecision : Serializable {
    data class Owner(val id: Long) : IdempotencyDecision

    data class Replay(val attemptId: UUID, val completed: Boolean) : IdempotencyDecision

    data object InProgress : IdempotencyDecision

    data object Conflict : IdempotencyDecision
}

/** PostgreSQL authority for principal-scoped replay and fingerprint conflicts. */
class HttpIdempotencyRepository(
    private val jdbc: TicketJdbcExecutor,
    private val retention: Duration = Duration.ofHours(24),
) {
    fun acquire(
        scope: IdempotencyScope,
        fingerprint: TicketDigest,
        now: Instant,
    ): IdempotencyDecision =
        try {
            jdbc.transaction {
                acquire(TicketLockRank.IDEMPOTENCY)
                connection.prepareStatement(
                    """
                    INSERT INTO ticket_http_idempotency(
                        principal_subject_id, http_method, canonical_route, resource_id, operation,
                        idempotency_key_digest, request_fingerprint, status, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'in_progress', ?) RETURNING id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, scope.principalSubjectId)
                    statement.setString(2, scope.httpMethod)
                    statement.setString(3, scope.canonicalRoute)
                    statement.setString(4, scope.resourceId)
                    statement.setString(5, scope.operation)
                    statement.setBytes(6, scope.keyDigest.bytes())
                    statement.setBytes(7, fingerprint.bytes())
                    statement.setObject(8, now.plus(retention).atOffset(ZoneOffset.UTC))
                    statement.executeQuery().use { result ->
                        check(result.next())
                        IdempotencyDecision.Owner(result.getLong(1))
                    }
                }
            }
        } catch (failure: SQLException) {
            if (failure.sqlState != UNIQUE_VIOLATION) throw failure
            existing(scope, fingerprint)
        }

    fun attachAttempt(
        id: Long,
        attemptId: UUID,
    ) {
        jdbc.transaction {
            acquire(TicketLockRank.IDEMPOTENCY)
            connection.prepareStatement(
                "UPDATE ticket_http_idempotency SET attempt_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, attemptId)
                statement.setLong(2, id)
                check(statement.executeUpdate() == 1) { "idempotency row not found" }
            }
        }
    }

    private fun existing(
        scope: IdempotencyScope,
        fingerprint: TicketDigest,
    ): IdempotencyDecision =
        jdbc.transaction {
            acquire(TicketLockRank.IDEMPOTENCY)
            connection.prepareStatement(
                """
                SELECT request_fingerprint, status, attempt_id
                FROM ticket_http_idempotency
                WHERE principal_subject_id = ? AND http_method = ? AND canonical_route = ?
                  AND resource_id = ? AND operation = ? AND idempotency_key_digest = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, scope.principalSubjectId)
                statement.setString(2, scope.httpMethod)
                statement.setString(3, scope.canonicalRoute)
                statement.setString(4, scope.resourceId)
                statement.setString(5, scope.operation)
                statement.setBytes(6, scope.keyDigest.bytes())
                statement.executeQuery().use { result ->
                    check(result.next()) { "idempotency winner disappeared" }
                    if (!result.getBytes("request_fingerprint").contentEquals(fingerprint.bytes())) {
                        IdempotencyDecision.Conflict
                    } else {
                        val attemptId = result.getObject("attempt_id", UUID::class.java)
                        when {
                            attemptId != null ->
                                IdempotencyDecision.Replay(attemptId, result.getString("status") == "completed")
                            else -> IdempotencyDecision.InProgress
                        }
                    }
                }
            }
        }

    companion object {
        private const val UNIQUE_VIOLATION = "23505"
    }
}
